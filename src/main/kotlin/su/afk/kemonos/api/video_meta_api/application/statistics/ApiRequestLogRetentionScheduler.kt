package su.afk.kemonos.api.video_meta_api.application.statistics

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.ApiRequestLogRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Удаляет устаревшие записи статистики запросов.
 *
 * Без этого таблица растёт бесконечно, а агрегация по версиям клиента
 * каждый раз выполняет full scan по всей истории.
 */
@Component
@Lazy(false)
class ApiRequestLogRetentionScheduler(
    private val requestLogRepository: ApiRequestLogRepository,
    @Value("\${app.statistics.retention-days:90}")
    private val retentionDays: Long,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${app.statistics.retention.initial-delay-millis:120000}",
        fixedDelayString = "\${app.statistics.retention.fixed-delay-millis:86400000}",
    )
    fun purgeOldRequests() {
        if (retentionDays <= 0) return
        val threshold = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        val removed = runCatching { requestLogRepository.deleteOlderThan(threshold) }
            .onFailure { ex -> logger.warn("Could not purge old api_request_log rows: {}", ex.message) }
            .getOrDefault(0)
        if (removed > 0) {
            logger.info("Purged {} api_request_log rows older than {} days", removed, retentionDays)
        }
    }
}
