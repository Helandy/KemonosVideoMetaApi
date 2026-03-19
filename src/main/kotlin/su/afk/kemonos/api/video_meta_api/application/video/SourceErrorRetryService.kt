package su.afk.kemonos.api.video_meta_api.application.video

import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity

/**
 * Повторно выполняет запрос по записи из журнала ошибок источников.
 */
interface SourceErrorRetryService {
    fun retrySourceError(errorLog: SourceErrorLogEntity): Boolean
}
