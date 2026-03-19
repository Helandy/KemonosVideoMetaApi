package su.afk.kemonos.api.video_meta_api.domain.video.model

import java.time.Instant

/**
 * Нормализованный запрос на чтение или удаление метаданных медиа.
 */
data class VideoMetaLookupRequest(
    val site: String? = null,
    val server: String? = null,
    val path: String? = null,
)

/**
 * Доменная модель результата чтения метаданных медиа.
 */
data class VideoMetaSnapshot(
    val site: String?,
    val server: String?,
    val path: String,
    val ext: String?,
    val sizeBytes: Long,
    val durationSeconds: Long,
    val lastStatusCode: Int,
    val available: Boolean,
    val createdAt: Instant,
)

/**
 * Доменная модель результата удаления записи медиа.
 */
data class VideoMetaDeleteResult(
    val removed: Boolean,
    val resolvedUrl: String,
    val removedThumbnails: Boolean,
)
