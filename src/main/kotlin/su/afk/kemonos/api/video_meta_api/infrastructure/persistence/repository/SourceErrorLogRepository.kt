package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.converter.SqliteInstantConverter
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity
import java.sql.ResultSet
import java.time.Instant
import kotlin.math.max

/**
 * Репозиторий записей об ошибках удалённых источников.
 */
@Repository
class SourceErrorLogRepository(
    @Qualifier("sourceErrorLogJdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
) {
    private val instantConverter = SqliteInstantConverter()

    init {
        initializeSchema()
        normalizeCreatedAtValues()
    }

    /**
     * Возвращает последние ошибки в порядке убывания времени создания.
     */
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<SourceErrorLogEntity> =
        jdbcTemplate.query(
            """
            select
                id,
                client_version,
                endpoint,
                site,
                request_value,
                requested_url,
                source_url,
                stage,
                status_code,
                error_message,
                retary,
                requests,
                created_at
            from source_error_log
            order by created_at desc
            limit ? offset ?
            """.trimIndent(),
            ::mapRow,
            pageable.pageSize,
            pageable.offset.toInt(),
        )

    fun findAllByOrderByCreatedAtAsc(pageable: Pageable): List<SourceErrorLogEntity> =
        jdbcTemplate.query(
            """
            select
                id,
                client_version,
                endpoint,
                site,
                request_value,
                requested_url,
                source_url,
                stage,
                status_code,
                error_message,
                retary,
                requests,
                created_at
            from source_error_log
            order by created_at asc
            limit ? offset ?
            """.trimIndent(),
            ::mapRow,
            pageable.pageSize,
            pageable.offset.toInt(),
        )

    fun count(): Long =
        jdbcTemplate.queryForObject("select count(*) from source_error_log", Long::class.java) ?: 0L

    @Synchronized
    fun save(entity: SourceErrorLogEntity): SourceErrorLogEntity {
        val existing = findExisting(entity)
        if (existing != null) {
            jdbcTemplate.update(
                """
                update source_error_log
                set
                    requests = requests + ?,
                    retary = ?,
                    created_at = ?
                where id = ?
                """.trimIndent(),
                entity.requests,
                max(existing.retary, entity.retary),
                formatInstant(maxInstant(existing.createdAt, entity.createdAt)),
                existing.id,
            )
            return existing.copy(
                requests = existing.requests + entity.requests,
                retary = max(existing.retary, entity.retary),
                createdAt = maxInstant(existing.createdAt, entity.createdAt),
            )
        }

        jdbcTemplate.update(
            """
            insert into source_error_log (
                client_version,
                endpoint,
                site,
                request_value,
                requested_url,
                source_url,
                stage,
                status_code,
                error_message,
                retary,
                requests,
                created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            entity.clientVersion,
            entity.endpoint,
            entity.site,
            entity.requestValue,
            entity.requestedUrl,
            entity.sourceUrl,
            entity.stage,
            entity.statusCode,
            entity.errorMessage,
            entity.retary,
            entity.requests,
            formatInstant(entity.createdAt),
        )
        return entity
    }

    private fun initializeSchema() {
        jdbcTemplate.execute(
            """
            create table if not exists source_error_log (
                id integer primary key autoincrement,
                client_version text not null,
                endpoint text not null,
                site text,
                request_value text,
                requested_url text,
                source_url text,
                stage text not null,
                status_code integer not null,
                error_message text,
                retary integer not null default 0,
                requests integer not null default 1,
                created_at text not null
            )
            """.trimIndent(),
        )
        addColumnIfMissing("retary", "integer not null default 0")
        addColumnIfMissing("requests", "integer not null default 1")
        jdbcTemplate.execute(
            "create index if not exists idx_source_error_log_created_at on source_error_log (created_at)",
        )
        jdbcTemplate.execute(
            "create index if not exists idx_source_error_log_status_code on source_error_log (status_code)",
        )
        jdbcTemplate.execute(
            "create index if not exists idx_source_error_log_endpoint on source_error_log (endpoint)",
        )
    }

    private fun normalizeCreatedAtValues() {
        val rows = jdbcTemplate.query(
            "select id, created_at from source_error_log",
        ) { rs, _ ->
            rs.getLong("id") to rs.getString("created_at")
        }

        rows.forEach { (id, rawCreatedAt) ->
            val normalized = formatInstant(parseInstant(rawCreatedAt))
            if (normalized != rawCreatedAt) {
                jdbcTemplate.update(
                    "update source_error_log set created_at = ? where id = ?",
                    normalized,
                    id,
                )
            }
        }
    }

    private fun addColumnIfMissing(columnName: String, definition: String) {
        val columns = jdbcTemplate.query(
            "pragma table_info(source_error_log)",
        ) { rs, _ -> rs.getString("name") }.toSet()
        if (columnName in columns) return
        jdbcTemplate.execute("alter table source_error_log add column $columnName $definition")
    }

    private fun findExisting(entity: SourceErrorLogEntity): SourceErrorLogEntity? =
        jdbcTemplate.query(
            """
            select
                id,
                client_version,
                endpoint,
                site,
                request_value,
                requested_url,
                source_url,
                stage,
                status_code,
                error_message,
                retary,
                requests,
                created_at
            from source_error_log
            where client_version = ?
              and endpoint = ?
              and ifnull(site, '') = ifnull(?, '')
              and ifnull(request_value, '') = ifnull(?, '')
              and ifnull(requested_url, '') = ifnull(?, '')
              and ifnull(source_url, '') = ifnull(?, '')
              and stage = ?
              and status_code = ?
              and ifnull(error_message, '') = ifnull(?, '')
            limit 1
            """.trimIndent(),
            ::mapRow,
            entity.clientVersion,
            entity.endpoint,
            entity.site,
            entity.requestValue,
            entity.requestedUrl,
            entity.sourceUrl,
            entity.stage,
            entity.statusCode,
            entity.errorMessage,
        ).firstOrNull()

    fun updateRetary(id: Long, retary: Int) {
        jdbcTemplate.update(
            "update source_error_log set retary = ? where id = ?",
            retary,
            id,
        )
    }

    fun deleteById(id: Long) {
        jdbcTemplate.update("delete from source_error_log where id = ?", id)
    }

    private fun maxInstant(left: Instant, right: Instant): Instant =
        if (left.isAfter(right)) left else right

    private fun parseInstant(rawValue: String?): Instant =
        requireNotNull(instantConverter.convertToEntityAttribute(rawValue)) {
            "Could not read source_error_log.created_at: value is null or blank"
        }

    private fun formatInstant(value: Instant): String =
        requireNotNull(instantConverter.convertToDatabaseColumn(value)) {
            "Could not write source_error_log.created_at"
        }

    private fun mapRow(rs: ResultSet, rowNum: Int): SourceErrorLogEntity =
        SourceErrorLogEntity(
            id = rs.getLong("id"),
            clientVersion = rs.getString("client_version"),
            endpoint = rs.getString("endpoint"),
            site = rs.getString("site"),
            requestValue = rs.getString("request_value"),
            requestedUrl = rs.getString("requested_url"),
            sourceUrl = rs.getString("source_url"),
            stage = rs.getString("stage"),
            statusCode = rs.getInt("status_code"),
            errorMessage = rs.getString("error_message"),
            retary = rs.getInt("retary"),
            requests = rs.getLong("requests"),
            createdAt = parseInstant(rs.getString("created_at")),
        )
}
