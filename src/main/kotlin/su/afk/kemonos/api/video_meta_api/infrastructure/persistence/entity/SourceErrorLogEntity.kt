package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity

import java.time.Instant

/**
 * Запись журнала ошибок при обращении к удалённым источникам.
 */
data class SourceErrorLogEntity(
    var id: Long? = null,
    var clientVersion: String = "",
    var endpoint: String = "",
    var site: String? = null,
    var requestValue: String? = null,
    var requestedUrl: String? = null,
    var sourceUrl: String? = null,
    var stage: String = "",
    var statusCode: Int = 0,
    var errorMessage: String? = null,
    var retary: Int = 0,
    var requests: Long = 1,
    var createdAt: Instant = Instant.now(),
)
