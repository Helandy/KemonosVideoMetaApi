package su.afk.kemonos.api.video_meta_api.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * Извлекает и нормализует IP клиента из proxy-заголовков или socket-адреса.
 */
@Component
class ClientIpResolver {
    /**
     * Возвращает наиболее надёжный IP клиента для логирования и rate-limit.
     */
    fun resolve(request: HttpServletRequest): String {
        // Cloudflare sends the canonical client IP in this header.
        headerValue(request, "CF-Connecting-IP")?.let { return it }
        headerValue(request, "X-Real-IP")?.let { return it }
        firstForwardedFor(request)?.let { return it }
        return normalize(request.remoteAddr) ?: "unknown"
    }

    private fun firstForwardedFor(request: HttpServletRequest): String? {
        val value = request.getHeader("X-Forwarded-For") ?: return null
        val first = value.split(",").firstOrNull()?.trim()
        return normalize(first)
    }

    private fun headerValue(request: HttpServletRequest, name: String): String? =
        normalize(request.getHeader(name))

    private fun normalize(value: String?): String? {
        val normalized = value?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return null
        return normalized.take(64)
    }
}
