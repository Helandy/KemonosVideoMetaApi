package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.CreatedAtNormalizer
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.converter.SqliteInstantConverter
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.VideoMetaEntity
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant

/**
 * Репозиторий доступа к таблице метаданных видео.
 *
 * Работает напрямую через JDBC: ORM здесь не давала ничего, кроме постоянно
 * висящих в памяти метамодели и persistence-контекста.
 */
@Repository
class VideoMetaRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val instantConverter = SqliteInstantConverter()

    init {
        initializeSchema()
        CreatedAtNormalizer(jdbcTemplate, TABLE_NAME).normalizeOnce()
    }

    /**
     * Ищет запись по полному нормализованному URL источника.
     */
    fun findByResolvedUrl(resolvedUrl: String): VideoMetaEntity? =
        jdbcTemplate.query(
            "$SELECT_COLUMNS where resolved_url = ? limit 1",
            ::mapRow,
            resolvedUrl,
        ).firstOrNull()

    /**
     * Ищет запись по сайту, request-пути и типу медиа.
     */
    fun findBySiteAndRequestAndMediaType(site: String, request: String, mediaType: String): VideoMetaEntity? =
        jdbcTemplate.query(
            "$SELECT_COLUMNS where site = ? and request = ? and media_type = ? limit 1",
            ::mapRow,
            site,
            request,
            mediaType,
        ).firstOrNull()

    /**
     * Вставляет новую запись или обновляет существующую по её идентификатору.
     */
    fun save(entity: VideoMetaEntity): VideoMetaEntity {
        val id = entity.id
        if (id == null) {
            entity.id = insert(entity)
        } else {
            update(id, entity)
        }
        return entity
    }

    fun delete(entity: VideoMetaEntity) {
        val id = entity.id ?: return
        jdbcTemplate.update("delete from video_meta where id = ?", id)
    }

    private fun insert(entity: VideoMetaEntity): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            connection.prepareStatement(
                """
                insert into video_meta (
                    site,
                    server,
                    request,
                    ext,
                    resolved_url,
                    size_bytes,
                    duration_seconds,
                    last_status_code,
                    media_type,
                    thumbnails_ready,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setString(1, entity.site)
                setString(2, entity.server)
                setString(3, entity.request)
                setString(4, entity.ext)
                setString(5, entity.resolvedUrl)
                setLong(6, entity.sizeBytes)
                setObject(7, entity.durationSeconds)
                setObject(8, entity.lastStatusCode)
                setString(9, entity.mediaType)
                setInt(10, if (entity.thumbnailsReady) 1 else 0)
                setString(11, formatInstant(entity.createdAt))
            }
        }, keyHolder)
        return keyHolder.key?.toLong() ?: error("Could not obtain generated id for video_meta row")
    }

    private fun update(id: Long, entity: VideoMetaEntity) {
        jdbcTemplate.update(
            """
            update video_meta
            set
                site = ?,
                server = ?,
                request = ?,
                ext = ?,
                resolved_url = ?,
                size_bytes = ?,
                duration_seconds = ?,
                last_status_code = ?,
                media_type = ?,
                thumbnails_ready = ?,
                created_at = ?
            where id = ?
            """.trimIndent(),
            entity.site,
            entity.server,
            entity.request,
            entity.ext,
            entity.resolvedUrl,
            entity.sizeBytes,
            entity.durationSeconds,
            entity.lastStatusCode,
            entity.mediaType,
            if (entity.thumbnailsReady) 1 else 0,
            formatInstant(entity.createdAt),
            id,
        )
    }

    /**
     * Создаёт таблицу и индекс, если их ещё нет.
     *
     * На существующих базах, где схему создавал Hibernate, выражения ничего не меняют:
     * имена колонок совпадают с теми, что давала стратегия именования по умолчанию.
     */
    private fun initializeSchema() {
        jdbcTemplate.execute(
            """
            create table if not exists video_meta (
                id integer primary key autoincrement,
                site text,
                server text,
                request text not null,
                ext text,
                resolved_url text not null unique,
                size_bytes integer not null,
                duration_seconds integer,
                last_status_code integer,
                media_type text not null,
                thumbnails_ready integer not null,
                created_at text not null
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create index if not exists idx_video_meta_site_request_media_type
            on video_meta (site, request, media_type)
            """.trimIndent(),
        )
    }

    private fun mapRow(rs: ResultSet, rowNum: Int): VideoMetaEntity =
        VideoMetaEntity(
            id = rs.getLong("id"),
            site = rs.getString("site"),
            server = rs.getString("server"),
            request = rs.getString("request").orEmpty(),
            ext = rs.getString("ext"),
            resolvedUrl = rs.getString("resolved_url").orEmpty(),
            sizeBytes = rs.getLong("size_bytes"),
            durationSeconds = rs.getLong("duration_seconds").takeIf { !rs.wasNull() },
            lastStatusCode = rs.getInt("last_status_code").takeIf { !rs.wasNull() },
            mediaType = rs.getString("media_type").orEmpty(),
            thumbnailsReady = rs.getBoolean("thumbnails_ready"),
            createdAt = parseInstant(rs.getString("created_at")),
        )

    private fun parseInstant(rawValue: String?): Instant =
        instantConverter.convertToEntityAttribute(rawValue) ?: Instant.EPOCH

    private fun formatInstant(value: Instant): String =
        requireNotNull(instantConverter.convertToDatabaseColumn(value)) {
            "Could not write video_meta.created_at"
        }

    private companion object {
        const val TABLE_NAME = "video_meta"
        const val SELECT_COLUMNS = """
            select
                id,
                site,
                server,
                request,
                ext,
                resolved_url,
                size_bytes,
                duration_seconds,
                last_status_code,
                media_type,
                thumbnails_ready,
                created_at
            from video_meta
        """
    }
}
