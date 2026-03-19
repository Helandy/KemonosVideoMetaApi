package su.afk.kemonos.api.video_meta_api.application.video

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.SourceErrorLogRepository

/**
 * Периодически повторяет запросы из журнала ошибок, начиная с самых старых.
 */
@Component
class SourceErrorRetryScheduler(
    private val sourceErrorLogRepository: SourceErrorLogRepository,
    private val sourceErrorRetryService: SourceErrorRetryService,
    @Value("\${app.source-error-log.retry.batch-size:25}")
    private val batchSize: Int,
    @Value("\${app.source-error-log.retry.max-retary:3}")
    private val maxRetary: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${app.source-error-log.retry.initial-delay-millis:600000}",
        fixedDelayString = "\${app.source-error-log.retry.fixed-delay-millis:600000}",
    )
    fun retryOldestErrors() {
        val rows = sourceErrorLogRepository.findAllByOrderByCreatedAtAsc(
            PageRequest.of(0, batchSize.coerceAtLeast(1)),
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
                val nextRetary = row.retary + 1
                if (nextRetary >= maxRetary) {
                    sourceErrorLogRepository.deleteById(rowId)
                    logger.warn(
                        "Retry failed for source error log id={}, reached retary={}, deleting row: {}",
                        rowId,
                        nextRetary,
                        ex.message,
                    )
                } else {
                    sourceErrorLogRepository.updateRetary(rowId, nextRetary)
                    logger.warn(
                        "Retry failed for source error log id={}, retary {} -> {}: {}",
                        rowId,
                        row.retary,
                        nextRetary,
                        ex.message,
                    )
                }
            }
    }
}
