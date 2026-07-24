package su.afk.kemonos.api.video_meta_api.application.video

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaDeleteResult
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaLookupRequest
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaSnapshot
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.ApiRequestLogEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.VideoMetaEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.ApiRequestLogRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.VideoMetaRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.thumbnail.ThumbnailGenerationService
import su.afk.kemonos.api.video_meta_api.infrastructure.thumbnail.ThumbnailMeta
import su.afk.kemonos.api.video_meta_api.infrastructure.source.SourceRequestLimiter
import java.net.URI
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * Основной application-service сценариев по видео и файлам.
 */
@Service
class VideoMetaService(
    private val repository: VideoMetaRepository,
    private val requestLogRepository: ApiRequestLogRepository,
    private val thumbnailGenerationService: ThumbnailGenerationService,
    private val sourceRequestLimiter: SourceRequestLimiter,
    private val inputResolver: VideoInputResolver,
    private val sourceMetadataReader: SourceMetadataReader,
    private val sourceErrorLogger: SourceErrorLogger,
) : SourceErrorRetryService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val inFlightInspects = ConcurrentHashMap<String, CompletableFuture<VideoMetaEntity>>()

    fun fileInfo(request: VideoMetaLookupRequest, clientVersion: String, clientKey: String): VideoMetaSnapshot =
        inspectAndSave(request, clientVersion, InspectMode.MEDIA, clientKey = clientKey).toDomainModel()

    fun videoInfo(request: VideoMetaLookupRequest, clientVersion: String, clientKey: String): VideoMetaSnapshot =
        inspectAndSave(request, clientVersion, InspectMode.VIDEO, clientKey = clientKey).toDomainModel()

    override fun retrySourceError(errorLog: SourceErrorLogEntity): Boolean {
        val request = buildRetryLookupRequest(errorLog)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not reconstruct retry request")
        val mode = when (errorLog.endpoint) {
            InspectMode.MEDIA.endpoint -> InspectMode.MEDIA
            InspectMode.VIDEO.endpoint -> InspectMode.VIDEO
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported retry endpoint: ${errorLog.endpoint}")
        }
        inspectAndSave(
            request = request,
            clientVersion = errorLog.clientVersion.ifBlank { "system-retry" },
            mode = mode,
            clientKey = "system-retry",
            logRequestEnabled = false,
            logErrorEnabled = false,
        )
        return true
    }

    fun removeVideo(request: VideoMetaLookupRequest): VideoMetaDeleteResult {
        val resolved = inputResolver.resolve(request)
        val existing = repository.findByResolvedUrl(resolved.url)
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

    private fun inspectAndSave(
        request: VideoMetaLookupRequest,
        clientVersion: String,
        mode: InspectMode,
        clientKey: String,
        logRequestEnabled: Boolean = true,
        logErrorEnabled: Boolean = true,
    ): VideoMetaEntity {
        val resolved = inputResolver.resolve(request)
        val normalizedSite = inputResolver.normalizeSiteKey(request.site, resolved.server)
        val requestValue = inputResolver.toRequestValue(resolved.path)
        val extValue = inputResolver.extractExtension(resolved.path)
        val errorLogContext = SourceErrorLogContext(
            clientVersion = clientVersion,
            endpoint = mode.endpoint,
            site = normalizedSite,
            requestValue = requestValue,
            requestedUrl = resolved.url,
            loggingEnabled = logErrorEnabled,
        )
        inputResolver.validateExtensionForMode(extValue, mode)
        if (logRequestEnabled) {
            logInspectRequest(
                clientVersion = clientVersion,
                requestValue = requestValue,
                resolvedUrl = resolved.url,
                endpoint = mode.endpoint,
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
        if (inFlight != null) return joinInspectFuture(inFlight)

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
                val remote = sourceMetadataReader.fetchRemoteMeta(
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

                val persisted = inputResolver.toPersistedResolvedInput(remote.sourceUrl, normalizedSite)

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
            sourceErrorLogger.logRequestFailure(errorLogContext, ex)
            currentFuture.completeExceptionally(ex)
            throw ex
        } finally {
            inFlightInspects.remove(inspectKey, currentFuture)
        }
    }

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
                sourceMetadataReader.fetchRemoteMeta(
                    url = existing.resolvedUrl.ifBlank { resolved.url },
                    siteRootUrl = resolved.siteRootUrl,
                    errorLogContext = SourceErrorLogContext(
                        clientVersion = "system-refresh",
                        endpoint = mode.endpoint,
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
        val sourceUrl = remoteRefreshed?.sourceUrl
            ?: sourceMetadataReader.resolveCachedSourceUrl(existing.resolvedUrl.ifBlank { resolved.url })
        if (remoteRefreshed != null) {
            validateRemoteMedia(remoteRefreshed)

            val persisted = inputResolver.toPersistedResolvedInput(remoteRefreshed.sourceUrl, normalizedSite)
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

    private fun findExistingEntity(
        resolved: ResolvedInput,
        normalizedSite: String?,
        requestValue: String,
        mode: InspectMode,
    ): VideoMetaEntity? {
        repository.findByResolvedUrl(resolved.url)?.let { return it }
        if (!normalizedSite.isNullOrBlank()) {
            repository.findBySiteAndRequestAndMediaType(normalizedSite, requestValue, mode.storageValue)
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

    private fun joinInspectFuture(future: CompletableFuture<VideoMetaEntity>): VideoMetaEntity =
        try {
            future.join()
        } catch (ex: CompletionException) {
            val cause = ex.cause
            if (cause is RuntimeException) throw cause
            throw ex
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

internal fun buildMirrorFallbackUrl(url: String, siteRootUrl: String?): String? =
    su.afk.kemonos.api.video_meta_api.infrastructure.source.buildMirrorFallbackUrl(url, siteRootUrl)
