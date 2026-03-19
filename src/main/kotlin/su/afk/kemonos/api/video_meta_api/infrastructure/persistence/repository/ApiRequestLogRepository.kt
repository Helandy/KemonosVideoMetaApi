package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.converter.SqliteInstantConverter
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.ApiRequestLogEntity
import java.time.Instant

/**
 * Репозиторий логов API-запросов.
 */
@Repository
class ApiRequestLogRepository(
    @Qualifier("statisticsJdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) {
    private val instantConverter = SqliteInstantConverter()

    init {
        initializeSchema()
        normalizeCreatedAtValues()
    }

    /**
     * Возвращает количество запросов, сгруппированное по версии клиента.
     */
    fun countRequestsByVersion(): List<VersionRequestsCountProjection> =
        jdbcTemplate.query(
            """
            select client_version, count(id) as requests_count
            from api_request_log
            group by client_version
            order by count(id) desc
            """.trimIndent(),
        ) { rs, _ ->
            VersionRequestsCountRow(
                clientVersion = rs.getString("client_version"),
                requestsCount = rs.getLong("requests_count"),
            )
        }

    fun save(entity: ApiRequestLogEntity): ApiRequestLogEntity {
        jdbcTemplate.update(
            """
            insert into api_request_log (
                client_version,
                endpoint,
                request_value,
                resolved_url,
                created_at
            ) values (?, ?, ?, ?, ?)
            """.trimIndent(),
            entity.clientVersion,
            entity.endpoint,
            entity.requestValue,
            entity.resolvedUrl,
            formatInstant(entity.createdAt),
        )
        return entity
    }

    private fun initializeSchema() {
        jdbcTemplate.execute(
            """
            create table if not exists api_request_log (
                id integer primary key autoincrement,
                client_version text not null,
                endpoint text not null,
                request_value text,
                resolved_url text,
                created_at text not null
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create index if not exists idx_api_request_log_version on api_request_log (client_version)",
        )
        jdbcTemplate.execute(
            "create index if not exists idx_api_request_log_created_at on api_request_log (created_at)",
        )
    }

    private fun normalizeCreatedAtValues() {
        val rows = jdbcTemplate.query(
            "select id, created_at from api_request_log",
        ) { rs, _ ->
            rs.getLong("id") to rs.getString("created_at")
        }

        rows.forEach { (id, rawCreatedAt) ->
            val normalized = formatInstant(parseInstant(rawCreatedAt))
            if (normalized != rawCreatedAt) {
                jdbcTemplate.update(
                    "update api_request_log set created_at = ? where id = ?",
                    normalized,
                    id,
                )
            }
        }
    }

    private fun parseInstant(rawValue: String?): Instant =
        requireNotNull(instantConverter.convertToEntityAttribute(rawValue)) {
            "Could not read api_request_log.created_at: value is null or blank"
        }

    private fun formatInstant(value: Instant): String =
        requireNotNull(instantConverter.convertToDatabaseColumn(value)) {
            "Could not write api_request_log.created_at"
        }
}

/**
 * Проекция результата агрегации запросов по версии клиента.
 */
interface VersionRequestsCountProjection {
    val clientVersion: String
    val requestsCount: Long
}

private data class VersionRequestsCountRow(
    override val clientVersion: String,
    override val requestsCount: Long,
) : VersionRequestsCountProjection
