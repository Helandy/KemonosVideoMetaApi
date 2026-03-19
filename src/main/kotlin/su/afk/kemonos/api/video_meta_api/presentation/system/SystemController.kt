package su.afk.kemonos.api.video_meta_api.presentation.system

import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.ApiRequestLogRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.SourceErrorLogRepository
import su.afk.kemonos.api.video_meta_api.presentation.system.dto.SourceErrorLogItemResponse
import su.afk.kemonos.api.video_meta_api.presentation.system.dto.SourceErrorLogRequest
import su.afk.kemonos.api.video_meta_api.presentation.system.dto.SourceErrorLogResponse
import su.afk.kemonos.api.video_meta_api.presentation.system.dto.StatusResponse
import su.afk.kemonos.api.video_meta_api.presentation.system.dto.VersionRequestCountResponse
import su.afk.kemonos.api.video_meta_api.presentation.system.dto.VersionStatsResponse
import java.time.Instant

/**
 * Служебные эндпоинты состояния сервиса и статистики по версиям клиентов.
 */
@RestController
class SystemController(
    private val requestLogRepository: ApiRequestLogRepository,
    private val sourceErrorLogRepository: SourceErrorLogRepository,
    @Value("\${app.version.cache-ttl-seconds:15}")
    versionCacheTtlSeconds: Long,
) {
    private val versionCacheTtlMillis = (versionCacheTtlSeconds.coerceAtLeast(0) * 1_000)
    @Volatile
    private var versionStatsCache: CachedVersionStats? = null

    /**
     * Возвращает простой health-check ответ.
     */
    @GetMapping("/status")
    fun status(): StatusResponse = StatusResponse(
        status = "UP",
        time = Instant.now(),
    )

    /**
     * Возвращает агрегированную статистику запросов по версиям клиента.
     */
    @GetMapping("/version")
    fun version(): VersionStatsResponse {
        if (versionCacheTtlMillis > 0) {
            val now = System.currentTimeMillis()
            versionStatsCache?.takeIf { it.expiresAtMillis > now }?.let { return it.payload }
        }

        val rows = requestLogRepository.countRequestsByVersion()
        val payload = VersionStatsResponse(
            totalRequests = rows.sumOf { it.requestsCount },
            versions = rows.map {
                VersionRequestCountResponse(
                    version = it.clientVersion,
                    requests = it.requestsCount,
                )
            },
        )
        if (versionCacheTtlMillis > 0) {
            versionStatsCache = CachedVersionStats(
                payload = payload,
                expiresAtMillis = System.currentTimeMillis() + versionCacheTtlMillis,
            )
        }
        return payload
    }

    /**
     * Возвращает последние ошибки удалённых источников.
     */
    @PostMapping("/errors/source")
    fun sourceErrors(
        @Valid @RequestBody request: SourceErrorLogRequest,
    ): SourceErrorLogResponse = loadSourceErrors(request.limit)

    /**
     * Возвращает последние ошибки удалённых источников для просмотра в браузере.
     */
    @GetMapping("/errors/source")
    fun sourceErrorsBrowser(
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): SourceErrorLogResponse = loadSourceErrors(limit)

    private fun loadSourceErrors(limit: Int): SourceErrorLogResponse {
        val normalizedLimit = limit.coerceIn(1, 500)
        val rows = sourceErrorLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, normalizedLimit))
        return SourceErrorLogResponse(
            total = sourceErrorLogRepository.count(),
            items = rows.map {
                SourceErrorLogItemResponse(
                    clientVersion = it.clientVersion,
                    endpoint = it.endpoint,
                    site = it.site,
                    requestValue = it.requestValue,
                    requestedUrl = it.requestedUrl,
                    sourceUrl = it.sourceUrl,
                    stage = it.stage,
                    statusCode = it.statusCode,
                    errorMessage = it.errorMessage,
                    retary = it.retary,
                    requests = it.requests,
                    createdAt = it.createdAt,
                )
            },
        )
    }
}

private data class CachedVersionStats(
    val payload: VersionStatsResponse,
    val expiresAtMillis: Long,
)
