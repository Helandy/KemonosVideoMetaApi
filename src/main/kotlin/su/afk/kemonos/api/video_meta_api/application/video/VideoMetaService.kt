package su.afk.kemonos.api.video_meta_api.application.video

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaDeleteResult
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaLookupRequest
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaSnapshot
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.ApiRequestLogEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.VideoMetaEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.ApiRequestLogRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.SourceErrorLogRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.VideoMetaRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.source.InspectRequestType
import su.afk.kemonos.api.video_meta_api.infrastructure.source.SourceRequestLimiter
import su.afk.kemonos.api.video_meta_api.infrastructure.source.normalizeSiteDomain
import su.afk.kemonos.api.video_meta_api.infrastructure.thumbnail.ThumbnailGenerationService
import su.afk.kemonos.api.video_meta_api.infrastructure.thumbnail.ThumbnailMeta
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Основной application-service сценариев по видео и файлам:
 * нормализует вход, получает данные из удалённого источника и сохраняет результат.
 */
@Service
class VideoMetaService(
    private val repository: VideoMetaRepository,
    private val requestLogRepository: ApiRequestLogRepository,
    private val sourceErrorLogRepository: SourceErrorLogRepository,
    private val thumbnailGenerationService: ThumbnailGenerationService,
    private val sourceRequestLimiter: SourceRequestLimiter,
) : SourceErrorRetryService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val allowedHostSuffixes = listOf("coomer.st", "coomer.su", "kemono.su", "kemono.cr")
    private val allowedAudioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "wma")
    private val allowedVideoExtensions = setOf(
        "mp4", "m4v", "mov", "webm", "avi",
        "wmv", "flv", "mpeg", "mpg", "3gp", "mkv",
    )

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(8))
        .build()
    private val inFlightInspects = ConcurrentHashMap<String, CompletableFuture<VideoMetaEntity>>()
    private val redirectCache = ConcurrentHashMap<String, CachedRedirect>()
    private val redirectCacheTtl: Duration = Duration.ofHours(6)

    /**
     * Возвращает метаданные файла без генерации миниатюр.
     */
    fun fileInfo(request: VideoMetaLookupRequest, clientVersion: String, clientKey: String): VideoMetaSnapshot =
        inspectAndSave(request, clientVersion, "/api/file/info", mode = InspectMode.MEDIA, clientKey = clientKey).toDomainModel()

    /**
     * Возвращает метаданные видео с генерацией миниатюр.
     */
    fun videoInfo(request: VideoMetaLookupRequest, clientVersion: String, clientKey: String): VideoMetaSnapshot =
        inspectAndSave(request, clientVersion, "/api/video/info", mode = InspectMode.VIDEO, clientKey = clientKey).toDomainModel()

    override fun retrySourceError(errorLog: SourceErrorLogEntity): Boolean {
        val request = buildRetryLookupRequest(errorLog)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not reconstruct retry request")
        val mode = when (errorLog.endpoint) {
            "/api/file/info" -> InspectMode.MEDIA
            "/api/video/info" -> InspectMode.VIDEO
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported retry endpoint: ${errorLog.endpoint}")
        }
        inspectAndSave(
            request = request,
            clientVersion = errorLog.clientVersion.ifBlank { "system-retry" },
            endpoint = errorLog.endpoint,
            mode = mode,
            clientKey = "system-retry",
            logRequestEnabled = false,
            logErrorEnabled = false,
        )
        return true
    }

    /**
     * Удаляет запись видео из БД и связанные превью с диска.
     */
    fun removeVideo(request: VideoMetaLookupRequest): VideoMetaDeleteResult {
        val resolved = resolveUrl(request)
        val existing = repository.findByResolvedUrl(resolved.url).orElse(null)
            ?: return VideoMetaDeleteResult(
                removed = false,
                resolvedUrl = resolved.url,
                removedThumbnails = false,
            )

        repository.delete(existing)
        val removedThumbs = thumbnailGenerationService.deleteThumbnailsByRequest(existing.request)
        return VideoMetaDeleteResult(
            removed = true,
            resolvedUrl = resolved.url,
            removedThumbnails = removedThumbs,
        )
    }

    /**
     * Единый сценарий:
     * нормализует вход, логирует запрос, читает/обновляет БД и при необходимости генерирует превью.
     */
    private fun inspectAndSave(
        request: VideoMetaLookupRequest,
        clientVersion: String,
        endpoint: String,
        mode: InspectMode,
        clientKey: String,
        logRequestEnabled: Boolean = true,
        logErrorEnabled: Boolean = true,
    ): VideoMetaEntity {
        val resolved = resolveUrl(request)
        val normalizedSite = normalizeSiteKey(request.site, resolved.server)
        val requestValue = toRequestValue(resolved.path)
        val extValue = extractExtension(resolved.path)
        val errorLogContext = ErrorLogContext(
            clientVersion = clientVersion,
            endpoint = endpoint,
            site = normalizedSite,
            requestValue = requestValue,
            requestedUrl = resolved.url,
            loggingEnabled = logErrorEnabled,
        )
        validateExtensionForMode(extValue, mode)
        if (logRequestEnabled) {
            logInspectRequest(
                clientVersion = clientVersion,
                requestValue = requestValue,
                resolvedUrl = resolved.url,
                endpoint = endpoint,
            )
        }

        val existing = findExistingEntity(resolved, normalizedSite, requestValue, mode)
        if (existing != null) {
            return updateExistingEntity(
                existing = existing,
                resolved = resolved,
                normalizedSite = normalizedSite,
                requestValue = requestValue,
                extValue = extValue,
                mode = mode,
            )
        }

        val inspectKey = buildInspectKey(normalizedSite, requestValue, mode, resolved.url)
        val currentFuture = CompletableFuture<VideoMetaEntity>()
        val inFlight = inFlightInspects.putIfAbsent(inspectKey, currentFuture)
        if (inFlight != null) {
            return try {
                inFlight.join()
            } catch (ex: CompletionException) {
                val cause = ex.cause
                if (cause is RuntimeException) throw cause
                throw ex
            }
        }

        try {
            val rechecked = findExistingEntity(resolved, normalizedSite, requestValue, mode)
            if (rechecked != null) {
                val updated = updateExistingEntity(
                    existing = rechecked,
                    resolved = resolved,
                    normalizedSite = normalizedSite,
                    requestValue = requestValue,
                    extValue = extValue,
                    mode = mode,
                )
                currentFuture.complete(updated)
                return updated
            }

            val result = sourceRequestLimiter.withPermit(mode.requestType, clientKey) {
                val remote = fetchRemoteMeta(
                    url = resolved.url,
                    siteRootUrl = resolved.siteRootUrl,
                    errorLogContext = errorLogContext,
                )
                validateRemoteMedia(remote)
                val thumbnails = if (mode == InspectMode.VIDEO) {
                    thumbnailGenerationService.generateBlocking(
                        requestValue = requestValue,
                        sourceUrl = remote.sourceUrl,
                        durationSeconds = remote.durationSeconds,
                        statusCode = remote.statusCode,
                    )
                } else {
                    ThumbnailMeta(ready = false)
                }

                val persisted = toPersistedResolvedInput(remote.sourceUrl, normalizedSite)

                val entity = VideoMetaEntity(
                    site = persisted.site ?: normalizedSite,
                    server = persisted.server,
                    request = requestValue,
                    ext = extValue,
                    resolvedUrl = persisted.url,
                    sizeBytes = remote.sizeBytes,
                    durationSeconds = remote.durationSeconds,
                    lastStatusCode = remote.statusCode,
                    mediaType = mode.storageValue,
                    thumbnailsReady = thumbnails.ready,
                )
                repository.save(entity)
            }
            currentFuture.complete(result)
            return result
        } catch (ex: Exception) {
            if (logErrorEnabled) {
                logRequestFailure(errorLogContext, ex)
            }
            currentFuture.completeExceptionally(ex)
            throw ex
        } finally {
            inFlightInspects.remove(inspectKey, currentFuture)
        }
    }

    /**
     * Обновляет существующую запись и для видео гарантирует, что превью уже сгенерированы на диске.
     */
    private fun updateExistingEntity(
        existing: VideoMetaEntity,
        resolved: ResolvedInput,
        normalizedSite: String?,
        requestValue: String,
        extValue: String?,
        mode: InspectMode,
    ): VideoMetaEntity {
        var changed = false
        if (normalizedSite != null && existing.site != normalizedSite) {
            existing.site = normalizedSite
            changed = true
        }
        if (existing.request != requestValue) {
            existing.request = requestValue
            changed = true
        }
        if (existing.ext != extValue) {
            existing.ext = extValue
            changed = true
        }
        if (mode == InspectMode.VIDEO && existing.mediaType != mode.storageValue) {
            existing.mediaType = mode.storageValue
            changed = true
        }

        val currentDuration = existing.durationSeconds ?: 0
        val currentStatus = existing.lastStatusCode ?: 0
        val remoteRefreshed = if (currentDuration <= 0 || currentStatus !in 200..299) {
            runCatching {
                fetchRemoteMeta(
                    url = existing.resolvedUrl.ifBlank { resolved.url },
                    siteRootUrl = resolved.siteRootUrl,
                    errorLogContext = ErrorLogContext(
                        clientVersion = "system-refresh",
                        endpoint = when (mode) {
                            InspectMode.MEDIA -> "/api/file/info"
                            InspectMode.VIDEO -> "/api/video/info"
                        },
                        site = normalizedSite,
                        requestValue = requestValue,
                        requestedUrl = existing.resolvedUrl.ifBlank { resolved.url },
                        loggingEnabled = true,
                    ),
                )
            }
                .onFailure { ex ->
                    logger.warn(
                        "Failed to refresh remote meta for existing record request={} url={}: {}",
                        requestValue,
                        resolved.url,
                        ex.message,
                    )
                }.getOrNull()
        } else {
            null
        }
        val sourceUrl = remoteRefreshed?.sourceUrl ?: resolveCachedSourceUrl(existing.resolvedUrl.ifBlank { resolved.url })
        if (remoteRefreshed != null) {
            validateRemoteMedia(remoteRefreshed)

            val persisted = toPersistedResolvedInput(remoteRefreshed.sourceUrl, normalizedSite)
            if (existing.site != (persisted.site ?: normalizedSite)) {
                existing.site = persisted.site ?: normalizedSite
                changed = true
            }
            if (existing.server != persisted.server) {
                existing.server = persisted.server
                changed = true
            }
            if (existing.resolvedUrl != persisted.url) {
                existing.resolvedUrl = persisted.url
                changed = true
            }
        }

        if (remoteRefreshed != null) {
            if (existing.sizeBytes != remoteRefreshed.sizeBytes) {
                existing.sizeBytes = remoteRefreshed.sizeBytes
                changed = true
            }
            if (existing.durationSeconds != remoteRefreshed.durationSeconds) {
                existing.durationSeconds = remoteRefreshed.durationSeconds
                changed = true
            }
            if (existing.lastStatusCode != remoteRefreshed.statusCode) {
                existing.lastStatusCode = remoteRefreshed.statusCode
                changed = true
            }
        }

        val effectiveDuration = existing.durationSeconds ?: 0
        val effectiveStatus = existing.lastStatusCode ?: 0
        if (mode == InspectMode.VIDEO) {
            val thumbnails = thumbnailGenerationService.generateBlocking(
                requestValue = requestValue,
                sourceUrl = sourceUrl,
                durationSeconds = effectiveDuration,
                statusCode = effectiveStatus,
            )
            if (existing.thumbnailsReady != thumbnails.ready) {
                existing.thumbnailsReady = thumbnails.ready
                changed = true
            }
        }

        return if (changed) repository.save(existing) else existing
    }

    /**
     * Сохраняет факт обращения к API для последующей статистики.
     */
    private fun logInspectRequest(clientVersion: String, requestValue: String, resolvedUrl: String, endpoint: String) {
        requestLogRepository.save(
            ApiRequestLogEntity(
                clientVersion = clientVersion,
                endpoint = endpoint,
                requestValue = requestValue,
                resolvedUrl = resolvedUrl,
                createdAt = Instant.now(),
            ),
        )
    }

    /**
     * Преобразует входной `path` в нормализованный URL, сервер и путь.
     */
    private fun resolveUrl(request: VideoMetaLookupRequest): ResolvedInput {
        val rawInput = extractInput(request)
        val pathInput = if (rawInput.startsWith("/")) rawInput else "/$rawInput"
        val normalizedSite = normalizeSiteInput(request.site)
        val siteRootUrl = resolveSiteRootUrl(request.site, request.server)
        val server = request.server?.trim()?.removeSuffix("/")
            ?: siteRootUrl
            ?: defaultServerBySite(normalizedSite)
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Provide either 'server' or known 'site' (kemono/coomer)",
            )
        val serverUri = runCatching { URI(server) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid server url")
        val serverHost = serverUri.host
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid server host")
        validateAllowedHostOrThrow(serverHost)
        val scheme = serverUri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid server scheme")
        }
        val path = normalizePathByHost(pathInput, serverHost)

        val url = "$server$path"
        if (runCatching { URI(url) }.isFailure) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resolved url: $url")
        }

        return ResolvedInput(
            url = url,
            server = server,
            path = path,
            siteRootUrl = siteRootUrl,
        )
    }

    /**
     * Извлекает строку входа из `path` и валидирует, что она не пустая.
     */
    private fun extractInput(request: VideoMetaLookupRequest): String {
        val value = request.path?.trim()

        if (value.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 'path'")
        }

        return value
    }

    /**
     * Возвращает сервер по короткому имени сайта.
     */
    private fun defaultServerBySite(site: String?): String? = when (site?.lowercase()) {
        "kemono", "kemono.su", "https://kemono.su" -> "https://kemono.su"
        "kemono.cr", "https://kemono.cr" -> "https://kemono.cr"
        "coomer", "coomer.st", "https://coomer.st" -> "https://coomer.st"
        "coomer.su", "https://coomer.su" -> "https://coomer.su"
        else -> null
    }

    /**
     * Нормализует имя сайта:
     * берёт явное `site`, либо вычисляет его по хосту сервера.
     */
    private fun normalizeSiteKey(site: String?, server: String): String? {
        val normalizedSite = normalizeSiteInput(site)
        if (!normalizedSite.isNullOrBlank()) return normalizedSite
        return normalizeSiteDomain(server)
    }

    private fun normalizeSiteInput(site: String?): String? = normalizeSiteDomain(site)

    /**
     * Получает удалённые метаданные:
     * размер через HEAD/Range и длительность через ffprobe.
     */
    private fun fetchRemoteMeta(url: String, siteRootUrl: String?, errorLogContext: ErrorLogContext): RemoteMeta {
        try {
            val preferredUrl = resolveCachedSourceUrl(url)
            val headResponse = sendHead(preferredUrl, siteRootUrl, errorLogContext)
            val effectiveHeadUrl = normalizeResponseUrlOrFallback(headResponse, preferredUrl)
            rememberRedirect(url, effectiveHeadUrl)
            if (headResponse.statusCode() !in 200..299) {
                return RemoteMeta(
                    sizeBytes = 0,
                    durationSeconds = 0,
                    statusCode = headResponse.statusCode(),
                    sourceUrl = effectiveHeadUrl,
                )
            }
            val headLength = headerAsLong(headResponse, "Content-Length")
            if (headLength != null && headLength > 0) {
                return RemoteMeta(
                    sizeBytes = headLength,
                    durationSeconds = fetchDurationSeconds(effectiveHeadUrl),
                    statusCode = headResponse.statusCode(),
                    sourceUrl = effectiveHeadUrl,
                )
            }

            val rangeResponse = sendRangeGet(effectiveHeadUrl, siteRootUrl, errorLogContext)
            val effectiveRangeUrl = normalizeResponseUrlOrFallback(rangeResponse, effectiveHeadUrl)
            rememberRedirect(url, effectiveRangeUrl)
            val fromRangeHeader = parseContentRangeTotal(rangeResponse)
            val fromLengthHeader = headerAsLong(rangeResponse, "Content-Length")
            val size = fromRangeHeader ?: fromLengthHeader

            if (size == null || size <= 0) {
                throw ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Remote host did not provide file size for $url",
                )
            }

            return RemoteMeta(
                sizeBytes = size,
                durationSeconds = fetchDurationSeconds(effectiveRangeUrl),
                statusCode = rangeResponse.statusCode(),
                sourceUrl = effectiveRangeUrl,
            )
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Request interrupted", ex)
        } catch (ex: IOException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch metadata", ex)
        }
    }

    /**
     * Сохраняет перенаправление источника в кеш, если URL изменился.
     */
    private fun rememberRedirect(originalUrl: String, effectiveUrl: String) {
        if (originalUrl == effectiveUrl) return
        redirectCache[originalUrl] = CachedRedirect(
            url = effectiveUrl,
            expiresAt = Instant.now().plus(redirectCacheTtl),
        )
    }

    /**
     * Возвращает кешированный URL-источник, если запись ещё не истекла.
     */
    private fun resolveCachedSourceUrl(originalUrl: String): String {
        val cached = redirectCache[originalUrl] ?: return originalUrl
        if (cached.expiresAt.isBefore(Instant.now())) {
            redirectCache.remove(originalUrl, cached)
            return originalUrl
        }
        return cached.url
    }

    private fun findExistingEntity(
        resolved: ResolvedInput,
        normalizedSite: String?,
        requestValue: String,
        mode: InspectMode,
    ): VideoMetaEntity? {
        repository.findByResolvedUrl(resolved.url).orElse(null)?.let { return it }
        if (!normalizedSite.isNullOrBlank()) {
            repository.findBySiteAndRequestAndMediaType(normalizedSite, requestValue, mode.storageValue)
                .orElse(null)
                ?.let { return it }
        }
        return null
    }

    private fun buildInspectKey(
        normalizedSite: String?,
        requestValue: String,
        mode: InspectMode,
        fallbackUrl: String,
    ): String = if (normalizedSite.isNullOrBlank()) {
        "${mode.storageValue}:$fallbackUrl"
    } else {
        "${mode.storageValue}:$normalizedSite:$requestValue"
    }

    /**
     * Берёт фактический URL ответа HTTP, иначе возвращает fallback.
     */
    private fun normalizeResponseUrlOrFallback(response: HttpResponse<Void>, fallbackUrl: String): String {
        val responseUrl = response.uri()?.toString().orEmpty()
        return if (responseUrl.isBlank()) fallbackUrl else responseUrl
    }

    /**
     * Приводит путь к формату `request` без расширения файла.
     */
    private fun toRequestValue(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val normalized = if (normalizedPath.startsWith("/data/")) normalizedPath.removePrefix("/data") else normalizedPath
        val slashIndex = normalized.lastIndexOf('/')
        if (slashIndex < 0 || slashIndex == normalized.length - 1) return normalized
        val prefix = normalized.substring(0, slashIndex + 1)
        val fileName = normalized.substring(slashIndex + 1)
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex <= 0) return normalized
        return prefix + fileName.substring(0, dotIndex)
    }

    /**
     * Нормализует путь в зависимости от хоста.
     * Для coomer/kemono добавляет префикс `/data`, если его нет.
     */
    private fun normalizePathByHost(path: String, host: String?): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val normalizedHost = host?.lowercase(Locale.ROOT).orEmpty()
        val shouldUseDataPrefix = allowedHostSuffixes.any {
            normalizedHost == it || normalizedHost.endsWith(".$it")
        }
        if (!shouldUseDataPrefix) return normalizedPath
        if (normalizedPath.startsWith("/data/")) return normalizedPath
        return "/data$normalizedPath"
    }

    /**
     * Проверяет, что хост входит в список разрешённых доменов.
     */
    private fun validateAllowedHostOrThrow(host: String) {
        val normalized = host.lowercase(Locale.ROOT)
        val allowed = allowedHostSuffixes.any { normalized == it || normalized.endsWith(".$it") }
        if (!allowed) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Host is not allowed",
            )
        }
    }

    /**
     * Извлекает расширение файла из пути в нижнем регистре с префиксом точки.
     */
    private fun extractExtension(path: String): String? {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val fileName = normalized.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "")
        if (ext.isBlank() || ext == fileName) return null
        return ".${ext.lowercase(Locale.ROOT)}"
    }

    /**
     * Проверяет, что расширение соответствует типу эндпоинта.
     */
    private fun validateExtensionForMode(ext: String?, mode: InspectMode) {
        val normalized = ext?.removePrefix(".")?.lowercase(Locale.ROOT)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File extension is required")
        val allowed = when (mode) {
            InspectMode.MEDIA -> allowedAudioExtensions
            InspectMode.VIDEO -> allowedVideoExtensions
        }
        if (normalized !in allowed) {
            val type = when (mode) {
                InspectMode.MEDIA -> "audio"
                InspectMode.VIDEO -> "video"
            }
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported $type extension: .$normalized",
            )
        }
    }

    /**
     * Проверяет, что удалённый источник является доступным медиафайлом с длительностью.
     */
    private fun validateRemoteMedia(remote: RemoteMeta) {
        if (remote.statusCode !in 200..299) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Remote source is not available")
        }
        if (remote.sizeBytes <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Remote source has invalid size")
        }
        if (remote.durationSeconds <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Remote source is not a valid media file")
        }
    }

    /**
     * Получает длительность видео в секундах через ffprobe.
     */
    private fun fetchDurationSeconds(url: String): Long {
        val process = try {
            ProcessBuilder(
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                url,
            ).start()
        } catch (ex: IOException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "ffprobe is required to get duration (install ffmpeg/ffprobe)",
                ex,
            )
        }

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        process.inputStream.use { it.copyTo(stdout) }
        process.errorStream.use { it.copyTo(stderr) }

        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "ffprobe timeout for $url")
        }

        if (process.exitValue() != 0) {
            val errorText = stderr.toString().trim().ifBlank { "unknown ffprobe error" }
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "ffprobe failed: $errorText")
        }

        val durationRaw = stdout.toString().trim()
        val seconds = durationRaw.toDoubleOrNull()?.roundToLong()
        if (seconds == null || seconds <= 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Could not parse duration from ffprobe output: '$durationRaw'",
            )
        }
        return seconds
    }

    /**
     * Выполняет HEAD-запрос к источнику.
     */
    private fun sendHead(url: String, siteRootUrl: String?, errorLogContext: ErrorLogContext): HttpResponse<Void> {
        return sendWithSiteFallback(url, siteRootUrl, "HEAD", errorLogContext) { targetUrl ->
            HttpRequest.newBuilder(URI(targetUrl))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        }
    }

    /**
     * Выполняет GET c заголовком Range для получения размера, если HEAD не дал длину.
     */
    private fun sendRangeGet(url: String, siteRootUrl: String?, errorLogContext: ErrorLogContext): HttpResponse<Void> {
        return sendWithSiteFallback(url, siteRootUrl, "RANGE_GET", errorLogContext) { targetUrl ->
            HttpRequest.newBuilder(URI(targetUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Range", "bytes=0-0")
                .GET()
                .build()
        }
    }

    /**
     * Если конкретный mirror вернул `5xx`, повторяет запрос через корневой URL сайта,
     * чтобы получить актуальный редирект на живой сервер с контентом.
     */
    private fun sendWithSiteFallback(
        originalUrl: String,
        siteRootUrl: String?,
        stage: String,
        errorLogContext: ErrorLogContext,
        requestFactory: (String) -> HttpRequest,
    ): HttpResponse<Void> {
        val primaryResponse = client.send(requestFactory(originalUrl), HttpResponse.BodyHandlers.discarding())
        logHttpFailureIfNeeded(
            context = errorLogContext,
            stage = "$stage:primary",
            response = primaryResponse,
            sourceUrl = normalizeResponseUrlOrFallback(primaryResponse, originalUrl),
            fallbackAttempted = false,
        )
        if (primaryResponse.statusCode() !in 500..599) {
            return primaryResponse
        }

        val fallbackUrl = buildMirrorFallbackUrl(originalUrl, siteRootUrl) ?: return primaryResponse
        logger.warn(
            "Source responded with {} for {}, retrying via {}",
            primaryResponse.statusCode(),
            originalUrl,
            fallbackUrl,
        )

        val fallbackResponse = client.send(requestFactory(fallbackUrl), HttpResponse.BodyHandlers.discarding())
        logHttpFailureIfNeeded(
            context = errorLogContext,
            stage = "$stage:fallback",
            response = fallbackResponse,
            sourceUrl = normalizeResponseUrlOrFallback(fallbackResponse, fallbackUrl),
            fallbackAttempted = true,
        )
        return fallbackResponse
    }

    /**
     * Читает заголовок ответа как `Long`, если значение присутствует и корректно.
     */
    private fun headerAsLong(response: HttpResponse<Void>, headerName: String): Long? =
        response.headers().firstValue(headerName).orElse(null)?.toLongOrNull()

    /**
     * Парсит общий размер файла из `Content-Range` заголовка.
     */
    private fun parseContentRangeTotal(response: HttpResponse<Void>): Long? {
        val contentRange = response.headers().firstValue("Content-Range").orElse(null) ?: return null
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex == -1 || slashIndex == contentRange.lastIndex) return null
        return contentRange.substring(slashIndex + 1).toLongOrNull()
    }

    private fun logHttpFailureIfNeeded(
        context: ErrorLogContext,
        stage: String,
        response: HttpResponse<Void>,
        sourceUrl: String,
        fallbackAttempted: Boolean,
    ) {
        if (!context.loggingEnabled) return
        val statusCode = response.statusCode()
        if (statusCode !in 400..599) return
        val message = if (fallbackAttempted) {
            "Remote source returned HTTP $statusCode after fallback"
        } else {
            "Remote source returned HTTP $statusCode"
        }
        saveSourceErrorLog(
            context = context,
            stage = stage,
            statusCode = statusCode,
            sourceUrl = sourceUrl,
            message = message,
            retary = if (fallbackAttempted) 1 else 0,
        )
    }

    private fun logRequestFailure(context: ErrorLogContext, ex: Exception) {
        if (!context.loggingEnabled) return
        val responseStatus = ex as? ResponseStatusException ?: return
        val statusCode = responseStatus.statusCode.value()
        if (statusCode !in 400..599) return
        saveSourceErrorLog(
            context = context,
            stage = "request",
            statusCode = statusCode,
            sourceUrl = context.requestedUrl,
            message = responseStatus.reason ?: ex.message ?: "Request failed",
            retary = 0,
        )
    }

    private fun saveSourceErrorLog(
        context: ErrorLogContext,
        stage: String,
        statusCode: Int,
        sourceUrl: String?,
        message: String?,
        retary: Int,
    ) {
        runCatching {
            sourceErrorLogRepository.save(
                SourceErrorLogEntity(
                    clientVersion = context.clientVersion,
                    endpoint = context.endpoint,
                    site = context.site,
                    requestValue = context.requestValue,
                    requestedUrl = context.requestedUrl,
                    sourceUrl = sourceUrl,
                    stage = stage,
                    statusCode = statusCode,
                    errorMessage = message?.take(1000),
                    retary = retary,
                ),
            )
        }.onFailure { logError ->
            logger.warn(
                "Failed to save source error log endpoint={} requestedUrl={}: {}",
                context.endpoint,
                context.requestedUrl,
                logError.message,
            )
        }
    }

    private fun resolveSiteRootUrl(site: String?, server: String?): String? {
        val siteValue = site?.trim().orEmpty()
        if (siteValue.isNotBlank()) {
            runCatching { URI(siteValue) }.getOrNull()?.let { siteUri ->
                val scheme = siteUri.scheme?.lowercase(Locale.ROOT)
                val host = siteUri.host?.lowercase(Locale.ROOT)
                if ((scheme == "http" || scheme == "https") && host != null) {
                    validateAllowedHostOrThrow(host)
                    return URI(scheme, siteUri.userInfo, host, -1, "", null, null).toString().removeSuffix("/")
                }
            }
            normalizeSiteDomain(siteValue)?.let { return "https://$it" }
        }

        val serverHost = server?.trim()?.let { runCatching { URI(it).host?.lowercase(Locale.ROOT) }.getOrNull() }
        return when {
            serverHost == null -> null
            serverHost.endsWith(".kemono.cr") || serverHost == "kemono.cr" -> "https://kemono.cr"
            serverHost.endsWith(".kemono.su") || serverHost == "kemono.su" -> "https://kemono.su"
            serverHost.endsWith(".coomer.st") || serverHost == "coomer.st" -> "https://coomer.st"
            serverHost.endsWith(".coomer.su") || serverHost == "coomer.su" -> "https://coomer.su"
            else -> null
        }
    }

    private fun toPersistedResolvedInput(sourceUrl: String, normalizedSite: String?): PersistedResolvedInput {
        val sourceUri = runCatching { URI(sourceUrl) }.getOrNull() ?: return PersistedResolvedInput(
            url = sourceUrl,
            server = sourceUrl.substringBeforeLast('/'),
            site = normalizedSite,
        )
        val scheme = sourceUri.scheme ?: "https"
        val authority = sourceUri.authority ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid source authority")
        sourceUri.path ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid source path")
        val server = URI(scheme, authority, null, null, null).toString().removeSuffix("/")
        return PersistedResolvedInput(
            url = sourceUrl,
            server = server,
            site = normalizedSite ?: normalizeSiteKey(null, server),
        )
    }

    private fun buildRetryLookupRequest(errorLog: SourceErrorLogEntity): VideoMetaLookupRequest? {
        val retryUrl = errorLog.requestedUrl ?: errorLog.sourceUrl ?: return null
        val uri = runCatching { URI(retryUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val authority = uri.authority ?: return null
        val rawPath = uri.rawPath ?: return null
        val pathWithQuery = buildString {
            append(rawPath)
            if (!uri.rawQuery.isNullOrBlank()) {
                append('?')
                append(uri.rawQuery)
            }
        }
        return VideoMetaLookupRequest(
            site = errorLog.site,
            server = URI(scheme, authority, null, null, null).toString().removeSuffix("/"),
            path = pathWithQuery,
        )
    }
}

/**
 * Нормализованный вход запроса для дальнейшей обработки.
 */
private data class ResolvedInput(
    val url: String,
    val server: String,
    val path: String,
    val siteRootUrl: String?,
)

private enum class InspectMode {
    MEDIA,
    VIDEO;

    val storageValue: String
        get() = when (this) {
            MEDIA -> "audio"
            VIDEO -> "video"
        }

    val requestType: InspectRequestType
        get() = when (this) {
            MEDIA -> InspectRequestType.FILE_INFO
            VIDEO -> InspectRequestType.VIDEO_INFO
        }
}

/**
 * Метаданные удалённого видео, полученные из сетевых запросов.
 */
private data class RemoteMeta(
    val sizeBytes: Long,
    val durationSeconds: Long,
    val statusCode: Int,
    val sourceUrl: String,
)

/**
 * Запись кеша перенаправлений источника с TTL.
 */
private data class CachedRedirect(
    val url: String,
    val expiresAt: Instant,
)

private data class PersistedResolvedInput(
    val url: String,
    val server: String,
    val site: String?,
)

private data class ErrorLogContext(
    val clientVersion: String,
    val endpoint: String,
    val site: String?,
    val requestValue: String,
    val requestedUrl: String,
    val loggingEnabled: Boolean,
)

internal fun buildMirrorFallbackUrl(url: String, siteRootUrl: String?): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val normalizedSiteRoot = siteRootUrl?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() } ?: return null
    val siteHost = runCatching { URI(normalizedSiteRoot).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
    if (host == siteHost) return null

    val fallbackUri = URI(
        URI(normalizedSiteRoot).scheme ?: uri.scheme ?: "https",
        uri.userInfo,
        siteHost,
        -1,
        uri.path,
        uri.query,
        uri.fragment,
    )
    return fallbackUri.toString()
}
