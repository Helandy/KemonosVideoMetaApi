package su.afk.kemonos.api.video_meta_api.presentation.video.dto

import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaDeleteResult
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaLookupRequest
import su.afk.kemonos.api.video_meta_api.domain.video.model.VideoMetaSnapshot
import java.time.Instant

/**
 * Входной запрос на получение метаданных видео/файла.
 */
data class VideoMetaRequest(
    val site: String? = null,
    val server: String? = null,
    val path: String? = null,
)

/**
 * Запрос на удаление записи видео и связанных превью.
 */
data class VideoMetaRemoveRequest(
    val site: String? = null,
    val server: String? = null,
    val path: String? = null,
) {
    /**
     * Преобразует запрос удаления в доменную модель запроса метаданных.
     */
    fun toDomain(): VideoMetaLookupRequest = VideoMetaLookupRequest(
        site = site,
        server = server,
        path = path,
    )
}

/**
 * Ответ API с метаданными видео и ссылками на превью.
 */
data class VideoMetaResponse(
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
 * Результат удаления записи видео.
 */
data class VideoMetaRemoveResponse(
    val removed: Boolean,
    val resolvedUrl: String,
    val removedThumbnails: Boolean,
)

/**
 * Преобразует входной DTO в доменную модель запроса.
 */
fun VideoMetaRequest.toDomain(): VideoMetaLookupRequest = VideoMetaLookupRequest(
    site = site,
    server = server,
    path = path,
)

/**
 * Преобразует доменную модель чтения в DTO ответа API.
 */
fun VideoMetaSnapshot.toDto(): VideoMetaResponse = VideoMetaResponse(
    site = site,
    server = server,
    path = path,
    ext = ext,
    sizeBytes = sizeBytes,
    durationSeconds = durationSeconds,
    lastStatusCode = lastStatusCode,
    available = available,
    createdAt = createdAt,
)

/**
 * Преобразует доменную модель удаления в DTO ответа API.
 */
fun VideoMetaDeleteResult.toDto(): VideoMetaRemoveResponse = VideoMetaRemoveResponse(
    removed = removed,
    resolvedUrl = resolvedUrl,
    removedThumbnails = removedThumbnails,
)
