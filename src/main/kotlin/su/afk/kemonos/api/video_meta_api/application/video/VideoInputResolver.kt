package su.afk.kemonos.api.video_meta_api.application.video

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import su.afk.kemonos.api.video_meta_api.config.SourceProperties
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaLookupRequest
import su.afk.kemonos.api.video_meta_api.infrastructure.source.normalizeSiteDomain
import java.net.URI
import java.util.Locale

@Service
class VideoInputResolver(
    private val sourceProperties: SourceProperties,
) {
    fun resolve(request: VideoMetaLookupRequest): ResolvedInput {
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

    fun normalizeSiteKey(site: String?, server: String): String? {
        val normalizedSite = normalizeSiteInput(site)
        if (!normalizedSite.isNullOrBlank()) return normalizedSite
        return normalizeSiteDomain(server)
    }

    fun toRequestValue(path: String): String {
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

    fun extractExtension(path: String): String? {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val fileName = normalized.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "")
        if (ext.isBlank() || ext == fileName) return null
        return ".${ext.lowercase(Locale.ROOT)}"
    }

    fun validateExtensionForMode(ext: String?, mode: InspectMode) {
        val normalized = ext?.removePrefix(".")?.lowercase(Locale.ROOT)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File extension is required")
        val allowed = when (mode) {
            InspectMode.MEDIA -> sourceProperties.allowedAudioExtensions
            InspectMode.VIDEO -> sourceProperties.allowedVideoExtensions
        }.mapTo(HashSet()) { it.removePrefix(".").lowercase(Locale.ROOT) }
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

    fun toPersistedResolvedInput(sourceUrl: String, normalizedSite: String?): PersistedResolvedInput {
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

    private fun extractInput(request: VideoMetaLookupRequest): String {
        val value = request.path?.trim()

        if (value.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 'path'")
        }

        return value
    }

    private fun defaultServerBySite(site: String?): String? = when (site?.lowercase()) {
        "kemono", "kemono.su", "https://kemono.su" -> "https://kemono.su"
        "kemono.cr", "https://kemono.cr" -> "https://kemono.cr"
        "coomer", "coomer.st", "https://coomer.st" -> "https://coomer.st"
        "coomer.su", "https://coomer.su" -> "https://coomer.su"
        else -> null
    }

    private fun normalizeSiteInput(site: String?): String? = normalizeSiteDomain(site)

    private fun normalizePathByHost(path: String, host: String?): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val normalizedHost = host?.lowercase(Locale.ROOT).orEmpty()
        val shouldUseDataPrefix = sourceProperties.allowedHostSuffixes.any {
            normalizedHost == it || normalizedHost.endsWith(".$it")
        }
        if (!shouldUseDataPrefix) return normalizedPath
        if (normalizedPath.startsWith("/data/")) return normalizedPath
        return "/data$normalizedPath"
    }

    private fun validateAllowedHostOrThrow(host: String) {
        val normalized = host.lowercase(Locale.ROOT)
        val allowed = sourceProperties.allowedHostSuffixes.any { normalized == it || normalized.endsWith(".$it") }
        if (!allowed) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Host is not allowed",
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
        return sourceProperties.allowedHostSuffixes.firstOrNull { suffix ->
            serverHost == suffix || serverHost?.endsWith(".$suffix") == true
        }?.let { "https://$it" }
    }
}

data class ResolvedInput(
    val url: String,
    val server: String,
    val path: String,
    val siteRootUrl: String?,
)

data class PersistedResolvedInput(
    val url: String,
    val server: String,
    val site: String?,
)
