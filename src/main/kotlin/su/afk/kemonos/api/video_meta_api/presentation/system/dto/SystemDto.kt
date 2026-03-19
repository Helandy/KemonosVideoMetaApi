package su.afk.kemonos.api.video_meta_api.presentation.system.dto

import java.time.Instant

/**
 * Ответ эндпоинта `/status`.
 */
data class StatusResponse(
    val status: String,
    val time: Instant,
)

/**
 * Ответ эндпоинта `/version` с общей статистикой.
 */
data class VersionStatsResponse(
    val totalRequests: Long,
    val versions: List<VersionRequestCountResponse>,
)

/**
 * Количество запросов для конкретной версии клиента.
 */
data class VersionRequestCountResponse(
    val version: String,
    val requests: Long,
)

/**
 * Запрос на чтение журнала ошибок удалённых источников.
 */
data class SourceErrorLogRequest(
    val limit: Int = 100,
)

/**
 * Ответ со списком последних ошибок удалённых источников.
 */
data class SourceErrorLogResponse(
    val total: Long,
    val items: List<SourceErrorLogItemResponse>,
)

/**
 * Одна запись журнала ошибок удалённого источника.
 */
data class SourceErrorLogItemResponse(
    val clientVersion: String,
    val endpoint: String,
    val site: String?,
    val requestValue: String?,
    val requestedUrl: String?,
    val sourceUrl: String?,
    val stage: String,
    val statusCode: Int,
    val errorMessage: String?,
    val retary: Int,
    val requests: Long,
    val createdAt: Instant,
)
