package su.afk.kemonos.api.video_meta_api.application.video

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import su.afk.kemonos.api.video_meta_api.config.SourceErrorRetryProperties
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.SourceErrorLogRepository

/**
 * Периодически повторяет запросы из журнала ошибок, начиная с самых старых.
 */
@Component
class SourceErrorRetryScheduler(
    private val sourceErrorLogRepository: SourceErrorLogRepository,
    private val sourceErrorRetryService: SourceErrorRetryService,
    private val properties: SourceErrorRetryProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${app.source-error-log.retry.initial-delay-millis:600000}",
        fixedDelayString = "\${app.source-error-log.retry.fixed-delay-millis:600000}",
    )
    fun retryOldestErrors() {
        val rows = sourceErrorLogRepository.findAllByOrderByCreatedAtAsc(
            PageRequest.of(0, properties.batchSize.coerceAtLeast(1)),
        )
        rows.forEach(::retrySingle)
    }

    private fun retrySingle(row: SourceErrorLogEntity) {
        val rowId = row.id ?: return
        runCatching { sourceErrorRetryService.retrySourceError(row) }
            .onSuccess {
                sourceErrorLogRepository.deleteById(rowId)
                logger.info("Retry succeeded for source error log id={}, deleting row", rowId)
            }
            .onFailure { ex ->
                val nextRetry = row.retry + 1
                if (nextRetry >= properties.maxRetry.coerceAtLeast(1)) {
                    sourceErrorLogRepository.deleteById(rowId)
                    logger.warn(
                        "Retry failed for source error log id={}, reached retry={}, deleting row: {}",
                        rowId,
                        nextRetry,
                        ex.message,
                    )
                } else {
                    sourceErrorLogRepository.updateRetry(rowId, nextRetry)
                    logger.warn(
                        "Retry failed for source error log id={}, retry {} -> {}: {}",
                        rowId,
                        row.retry,
                        nextRetry,
                        ex.message,
                    )
                }
            }
    }
}
