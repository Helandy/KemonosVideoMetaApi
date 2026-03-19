package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity

import java.time.Instant

/**
 * Запись лога входящих API-запросов для аналитики и статистики версий.
 */
data class ApiRequestLogEntity(
    var id: Long? = null,
    var clientVersion: String = "",
    var endpoint: String = "",
    var requestValue: String? = null,
    var resolvedUrl: String? = null,
    var createdAt: Instant = Instant.now(),
)
