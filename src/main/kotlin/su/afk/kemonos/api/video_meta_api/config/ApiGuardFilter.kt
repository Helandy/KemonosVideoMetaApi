package su.afk.kemonos.api.video_meta_api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Защитный фильтр API:
 * проверяет заголовок версии клиента и ограничивает частоту запросов по IP.
 */
@Component
class ApiGuardFilter(
    private val properties: RateLimitProperties,
    private val clientIpResolver: ClientIpResolver,
) : OncePerRequestFilter() {
    private val counters = ConcurrentHashMap<String, WindowCounter>()
    private val versionRegex = Regex("""^\d+\.\d+(\.\d+)?$""")

    @Volatile
    private var lastCleanupWindow: Long = Long.MIN_VALUE

    /**
     * Не применяет фильтр к раздаче миниатюр.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI ?: return false
        return path.startsWith("/thumbnail/")
    }

    /**
     * Проверяет rate limit и валидность заголовка `Kemonos` для API-маршрутов.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val nowWindow = currentWindow()
        val key = clientIpResolver.resolve(request)
        val newCount = counters.compute(key) { _, old ->
            if (old == null || old.window != nowWindow) {
                WindowCounter(nowWindow, 1)
            } else {
                old.copy(count = old.count + 1)
            }
        }!!.count

        cleanupOldEntries(nowWindow)

        if (newCount > properties.maxRequests.coerceAtLeast(1)) {
            writeError(
                response = response,
                status = 429,
                message = "Too Many Requests",
            )
            return
        }

        val path = request.requestURI ?: "/"
        if (path.startsWith("/api/")) {
            val version = request.getHeader("Kemonos")?.trim()
            if (version.isNullOrBlank() || !versionRegex.matches(version)) {
                writeError(
                    response = response,
                    status = 403,
                    message = "Forbidden",
                )
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * Возвращает номер текущего временного окна для rate limiting.
     */
    private fun currentWindow(): Long = System.currentTimeMillis() / (properties.windowSeconds.coerceAtLeast(1) * 1_000)

    /**
     * Очищает устаревшие счётчики не чаще одного раза за окно,
     * чтобы карта не росла бесконечно и при этом обход не выполнялся на каждом запросе.
     */
    private fun cleanupOldEntries(nowWindow: Long) {
        if (nowWindow == lastCleanupWindow) return
        if (counters.size < CLEANUP_SIZE_THRESHOLD) {
            lastCleanupWindow = nowWindow
            return
        }
        synchronized(this) {
            if (nowWindow == lastCleanupWindow) return
            lastCleanupWindow = nowWindow
        }
        val minWindowToKeep = nowWindow - 1
        counters.entries.removeIf { it.value.window < minWindowToKeep }
    }

    /**
     * Формирует и отправляет единый JSON-ответ об ошибке фильтра.
     */
    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        val error = when (status) {
            429 -> "Too Many Requests"
            403 -> "Forbidden"
            else -> "Bad Request"
        }
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        if (status == 429) {
            response.setHeader("Retry-After", properties.windowSeconds.coerceAtLeast(1).toString())
        }
        response.writer.write("""{"status":$status,"error":"$error","message":"$message"}""")
    }
}

/**
 * Начиная с этого размера карту счётчиков имеет смысл прореживать.
 */
private const val CLEANUP_SIZE_THRESHOLD = 2_000

/**
 * Счётчик запросов клиента в рамках одного временного окна.
 */
private data class WindowCounter(
    val window: Long,
    val count: Int,
)
