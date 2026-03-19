package su.afk.kemonos.api.video_meta_api.application.video

import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaSnapshot
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.VideoMetaEntity

/**
 * Конвертирует persistence-сущность в доменную модель ответа.
 */
fun VideoMetaEntity.toDomainModel(): VideoMetaSnapshot = VideoMetaSnapshot(
    site = site,
    server = server,
    path = request,
    ext = ext,
    sizeBytes = sizeBytes,
    durationSeconds = durationSeconds ?: 0,
    lastStatusCode = lastStatusCode ?: 0,
    available = when (mediaType) {
        "video" -> thumbnailsReady
        else -> (lastStatusCode ?: 0) in 200..299 && sizeBytes > 0 && (durationSeconds ?: 0) > 0
    },
    createdAt = createdAt,
)
