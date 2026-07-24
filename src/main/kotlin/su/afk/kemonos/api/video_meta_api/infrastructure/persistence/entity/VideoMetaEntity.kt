package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity

import java.time.Instant

/**
 * Запись метаданных видео из таблицы `video_meta`.
 */
class VideoMetaEntity(
    var id: Long? = null,
    var site: String? = null,
    var server: String? = null,
    var request: String = "",
    var ext: String? = null,
    var resolvedUrl: String = "",
    var sizeBytes: Long = 0,
    var durationSeconds: Long? = null,
    var lastStatusCode: Int? = null,
    var mediaType: String = "video",
    var thumbnailsReady: Boolean = false,
    var createdAt: Instant = Instant.now(),
)
