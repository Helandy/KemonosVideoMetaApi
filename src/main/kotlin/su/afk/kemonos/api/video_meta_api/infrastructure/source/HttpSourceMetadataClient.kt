package su.afk.kemonos.api.video_meta_api.infrastructure.source

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import su.afk.kemonos.api.video_meta_api.application.video.RemoteMeta
import su.afk.kemonos.api.video_meta_api.application.video.SourceErrorLogContext
import su.afk.kemonos.api.video_meta_api.application.video.SourceErrorLogger
import su.afk.kemonos.api.video_meta_api.application.video.SourceMetadataReader
import su.afk.kemonos.api.video_meta_api.infrastructure.process.ProcessRunner
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToLong

@Service
class HttpSourceMetadataClient(
    private val sourceErrorLogger: SourceErrorLogger,
    private val processRunner: ProcessRunner,
) : SourceMetadataReader {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(8))
        .build()
    private val redirectCacheTtl: Duration = Duration.ofHours(6)
    // LRU с жёсткой границей: записи, к которым больше не обращаются, не живут вечно.
    private val redirectCache = RedirectCache(maxEntries = REDIRECT_CACHE_MAX_ENTRIES)

    override fun fetchRemoteMeta(url: String, siteRootUrl: String?, errorLogContext: SourceErrorLogContext): RemoteMeta {
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

    override fun resolveCachedSourceUrl(originalUrl: String): String =
        redirectCache.get(originalUrl) ?: originalUrl

    private fun rememberRedirect(originalUrl: String, effectiveUrl: String) {
        if (originalUrl == effectiveUrl) return
        redirectCache.put(
            key = originalUrl,
            value = CachedRedirect(
                url = effectiveUrl,
                expiresAt = Instant.now().plus(redirectCacheTtl),
            ),
        )
    }

    private fun fetchDurationSeconds(url: String): Long {
        val result = try {
            processRunner.run(
                command = listOf(
                    "ffprobe",
                    "-v", "error",
                    "-protocol_whitelist", "https,http,tcp,tls,crypto",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    url,
                ),
                timeout = Duration.ofSeconds(20),
                // Ответ — одно число, держать килобайты под него незачем.
                outputLimitBytes = 512,
            )
        } catch (ex: IOException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "ffprobe is required to get duration (install ffmpeg/ffprobe)",
                ex,
            )
        }

        if (result.timedOut) {
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "ffprobe timeout for $url")
        }

        if (result.exitCode != 0) {
            val errorText = result.stderr.lines()
                .firstOrNull { it.isNotBlank() }?.take(120)
                ?: "unknown ffprobe error"
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "ffprobe failed: $errorText")
        }

        val durationRaw = result.stdout.trim()
        val seconds = durationRaw.toDoubleOrNull()?.roundToLong()
        if (seconds == null || seconds <= 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Could not parse duration from ffprobe output: '$durationRaw'",
            )
        }
        return seconds
    }

    private fun sendHead(url: String, siteRootUrl: String?, errorLogContext: SourceErrorLogContext): HttpResponse<Void> {
        return sendWithSiteFallback(url, siteRootUrl, "HEAD", errorLogContext) { targetUrl ->
            HttpRequest.newBuilder(URI(targetUrl))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        }
    }

    private fun sendRangeGet(url: String, siteRootUrl: String?, errorLogContext: SourceErrorLogContext): HttpResponse<Void> {
        return sendWithSiteFallback(url, siteRootUrl, "RANGE_GET", errorLogContext) { targetUrl ->
            HttpRequest.newBuilder(URI(targetUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Range", "bytes=0-0")
                .GET()
                .build()
        }
    }

    private fun sendWithSiteFallback(
        originalUrl: String,
        siteRootUrl: String?,
        stage: String,
        errorLogContext: SourceErrorLogContext,
        requestFactory: (String) -> HttpRequest,
    ): HttpResponse<Void> {
        val primaryResponse = client.send(requestFactory(originalUrl), HttpResponse.BodyHandlers.discarding())
        sourceErrorLogger.logHttpFailureIfNeeded(
            context = errorLogContext,
            stage = "$stage:primary",
            statusCode = primaryResponse.statusCode(),
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
        sourceErrorLogger.logHttpFailureIfNeeded(
            context = errorLogContext,
            stage = "$stage:fallback",
            statusCode = fallbackResponse.statusCode(),
            sourceUrl = normalizeResponseUrlOrFallback(fallbackResponse, fallbackUrl),
            fallbackAttempted = true,
        )
        return fallbackResponse
    }

    private fun normalizeResponseUrlOrFallback(response: HttpResponse<Void>, fallbackUrl: String): String {
        val responseUrl = response.uri()?.toString().orEmpty()
        return if (responseUrl.isBlank()) fallbackUrl else responseUrl
    }

    private fun headerAsLong(response: HttpResponse<Void>, headerName: String): Long? =
        response.headers().firstValue(headerName).orElse(null)?.toLongOrNull()

    private fun parseContentRangeTotal(response: HttpResponse<Void>): Long? {
        val contentRange = response.headers().firstValue("Content-Range").orElse(null) ?: return null
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex == -1 || slashIndex == contentRange.lastIndex) return null
        return contentRange.substring(slashIndex + 1).toLongOrNull()
    }
}

private const val REDIRECT_CACHE_MAX_ENTRIES = 5_000

private data class CachedRedirect(
    val url: String,
    val expiresAt: Instant,
)

/**
 * Кэш редиректов с ограниченным числом записей и TTL.
 *
 * Вытеснение по LRU гарантирует верхнюю границу занимаемой памяти независимо от того,
 * сколько уникальных URL прошло через сервис.
 */
private class RedirectCache(private val maxEntries: Int) {
    private val entries = object : LinkedHashMap<String, CachedRedirect>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRedirect>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): String? {
        val cached = entries[key] ?: return null
        if (cached.expiresAt.isBefore(Instant.now())) {
            entries.remove(key)
            return null
        }
        return cached.url
    }

    @Synchronized
    fun put(key: String, value: CachedRedirect) {
        entries[key] = value
    }
}

internal fun buildMirrorFallbackUrl(url: String, siteRootUrl: String?): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val normalizedSiteRoot = siteRootUrl?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() } ?: return null
    val siteHost = runCatching { URI(normalizedSiteRoot).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
    if (host == siteHost) return null

    val rootUri = runCatching { URI(normalizedSiteRoot) }.getOrNull() ?: return null
    val path = uri.rawPath ?: return null
    val query = uri.rawQuery
    return URI(
        rootUri.scheme ?: "https",
        rootUri.authority,
        path,
        query,
        null,
    ).toString()
}
