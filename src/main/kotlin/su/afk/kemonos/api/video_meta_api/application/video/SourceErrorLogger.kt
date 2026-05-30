package su.afk.kemonos.api.video_meta_api.application.video

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.SourceErrorLogRepository

@Service
class SourceErrorLogger(
    private val sourceErrorLogRepository: SourceErrorLogRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun logHttpFailureIfNeeded(
        context: SourceErrorLogContext,
        stage: String,
        statusCode: Int,
        sourceUrl: String,
        fallbackAttempted: Boolean,
    ) {
        if (!context.loggingEnabled || statusCode !in 400..599) return
        val message = if (fallbackAttempted) {
            "Remote source returned HTTP $statusCode after fallback"
        } else {
            "Remote source returned HTTP $statusCode"
        }
        saveSourceErrorLog(
            context = context,
            stage = stage,
            statusCode = statusCode,
            sourceUrl = sourceUrl,
            message = message,
            retry = if (fallbackAttempted) 1 else 0,
        )
    }

    fun logRequestFailure(context: SourceErrorLogContext, ex: Exception) {
        if (!context.loggingEnabled) return
        val responseStatus = ex as? ResponseStatusException ?: return
        val statusCode = responseStatus.statusCode.value()
        if (statusCode !in 400..599) return
        saveSourceErrorLog(
            context = context,
            stage = "request",
            statusCode = statusCode,
            sourceUrl = context.requestedUrl,
            message = responseStatus.reason ?: ex.message ?: "Request failed",
            retry = 0,
        )
    }

    private fun saveSourceErrorLog(
        context: SourceErrorLogContext,
        stage: String,
        statusCode: Int,
        sourceUrl: String?,
        message: String?,
        retry: Int,
    ) {
        runCatching {
            sourceErrorLogRepository.save(
                SourceErrorLogEntity(
                    clientVersion = context.clientVersion,
                    endpoint = context.endpoint,
                    site = context.site,
                    requestValue = context.requestValue,
                    requestedUrl = context.requestedUrl,
                    sourceUrl = sourceUrl,
                    stage = stage,
                    statusCode = statusCode,
                    errorMessage = message?.take(1000),
                    retry = retry,
                ),
            )
        }.onFailure { logError ->
            logger.warn(
                "Failed to save source error log endpoint={} requestedUrl={}: {}",
                context.endpoint,
                context.requestedUrl,
                logError.message,
            )
        }
    }
}

data class SourceErrorLogContext(
    val clientVersion: String,
    val endpoint: String,
    val site: String?,
    val requestValue: String,
    val requestedUrl: String,
    val loggingEnabled: Boolean,
)
