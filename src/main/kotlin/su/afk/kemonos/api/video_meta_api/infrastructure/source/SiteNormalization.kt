package su.afk.kemonos.api.video_meta_api.infrastructure.source

import java.net.URI
import java.util.Locale

private val canonicalSiteDomains = listOf("coomer.st", "coomer.su", "kemono.su", "kemono.cr")

/**
 * Нормализует входное значение сайта до канонического домена.
 */
internal fun normalizeSiteDomain(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return null
    val hostOrValue = extractHostOrValue(raw)
    return canonicalSiteDomains.firstOrNull { hostOrValue == it || hostOrValue.endsWith(".$it") }
        ?: when (hostOrValue) {
            "coomer" -> "coomer.st"
            "kemono" -> "kemono.su"
            else -> hostOrValue
        }
}

private fun extractHostOrValue(value: String): String {
    val host = runCatching { URI(value).host?.lowercase(Locale.ROOT) }.getOrNull()
    if (!host.isNullOrBlank()) return host

    val withoutScheme = value.substringAfter("://", value)
    val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.substringBefore(':').lowercase(Locale.ROOT)
}
