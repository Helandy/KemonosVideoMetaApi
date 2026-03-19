package su.afk.kemonos.api.video_meta_api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException
import su.afk.kemonos.api.video_meta_api.application.security.AdminKeyService
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Applies AdminKeyService brute-force protection for admin HTTP Basic auth endpoints.
 */
@Component
class AdminBruteforceProtectionFilter(
    private val adminKeyService: AdminKeyService,
    private val clientIpResolver: ClientIpResolver,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.orEmpty()
        return !(path.startsWith("/actuator/") || path == "/errors/source" || path == "/api/video/remove")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val credentials = parseBasicCredentials(request.getHeader("Authorization"))
        if (credentials == null || credentials.username != "admin") {
            filterChain.doFilter(request, response)
            return
        }

        val clientIp = clientIpResolver.resolve(request)
        try {
            adminKeyService.validateOrThrow(credentials.password, clientIp)
            filterChain.doFilter(request, response)
        } catch (ex: ResponseStatusException) {
            writeError(response, ex.statusCode.value(), ex.reason ?: "Forbidden")
        }
    }

    private fun parseBasicCredentials(header: String?): BasicCredentials? {
        if (header.isNullOrBlank() || !header.startsWith("Basic ")) return null
        val encoded = header.removePrefix("Basic ").trim()
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        val raw = decoded.toString(StandardCharsets.UTF_8)
        val separator = raw.indexOf(':')
        if (separator <= 0) return null
        val username = raw.substring(0, separator)
        val password = raw.substring(separator + 1)
        return BasicCredentials(username = username, password = password)
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        val error = when (status) {
            429 -> "Too Many Requests"
            403 -> "Forbidden"
            else -> "Unauthorized"
        }
        response.writer.write("""{"status":$status,"error":"$error","message":"$message"}""")
    }
}

private data class BasicCredentials(
    val username: String,
    val password: String,
)
