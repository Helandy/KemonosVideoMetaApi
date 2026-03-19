package su.afk.kemonos.api.video_meta_api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Логирует каждый HTTP-запрос в отдельные каналы:
 * access для успешных, badrequest для 4xx и error для 5xx/исключений.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class HttpRequestLogFilter(
    private val clientIpResolver: ClientIpResolver,
) : OncePerRequestFilter() {
    private val accessLogger = LoggerFactory.getLogger("http.access")
    private val badRequestLogger = LoggerFactory.getLogger("http.badrequest")
    private val errorLogger = LoggerFactory.getLogger("http.error")

    /**
     * Оборачивает выполнение запроса и гарантированно пишет запись в логи.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startNanos = System.nanoTime()
        var thrown: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (ex: Throwable) {
            thrown = ex
            throw ex
        } finally {
            logRequest(request, response, startNanos, thrown)
        }
    }

    /**
     * Формирует строку лога и отправляет её в нужный логгер по коду ответа.
     */
    private fun logRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
        startNanos: Long,
        thrown: Throwable?,
    ) {
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000
        val status = if (thrown != null && response.status < 400) 500 else response.status
        val method = request.method
        val path = request.requestURI + request.queryString?.let { "?$it" }.orEmpty()
        val ip = clientIpResolver.resolve(request)
        val kemonos = request.getHeader("Kemonos").orEmpty().take(32)
        val userAgent = request.getHeader("User-Agent").orEmpty().replace('\n', ' ').take(200)
        val referer = request.getHeader("Referer").orEmpty().take(200)
        val statusText = status.toString()

        val line = "ip={} method={} path=\"{}\" status={} durationMs={} kemonos={} ua=\"{}\" referer=\"{}\""
        when {
            thrown != null -> {
                errorLogger.error(
                    "$line errorClass={} errorMessage=\"{}\"",
                    ip,
                    method,
                    path,
                    statusText,
                    durationMs,
                    kemonos,
                    userAgent,
                    referer,
                    thrown.javaClass.simpleName,
                    (thrown.message ?: "").replace('\n', ' ').take(300),
                )
            }
            status >= 500 -> {
                errorLogger.error(
                    line,
                    ip,
                    method,
                    path,
                    statusText,
                    durationMs,
                    kemonos,
                    userAgent,
                    referer,
                )
            }
            status >= 400 -> {
                badRequestLogger.warn(
                    line,
                    ip,
                    method,
                    path,
                    statusText,
                    durationMs,
                    kemonos,
                    userAgent,
                    referer,
                )
            }
            else -> {
                accessLogger.info(
                    line,
                    ip,
                    method,
                    path,
                    statusText,
                    durationMs,
                    kemonos,
                    userAgent,
                    referer,
                )
            }
        }
    }
}
