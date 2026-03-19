package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.absolutePathString

class SourceErrorLogRepositoryTest {

    @Test
    fun `increments requests for duplicate error rows`() {
        val dbFile = Files.createTempFile("source-error-repository", ".db")
        val jdbcTemplate = JdbcTemplate(sqliteDataSource(dbFile.absolutePathString()))
        val repository = SourceErrorLogRepository(jdbcTemplate)

        repository.save(
            SourceErrorLogEntity(
                clientVersion = "1.0.0",
                endpoint = "/api/video/info",
                site = "kemono",
                requestValue = "/file/test",
                requestedUrl = "https://kemono.cr/data/file.mp4",
                sourceUrl = "https://kemono.cr/data/file.mp4",
                stage = "HEAD:primary",
                statusCode = 502,
                errorMessage = "Remote source returned HTTP 502",
                retary = 0,
                createdAt = Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )
        repository.save(
            SourceErrorLogEntity(
                clientVersion = "1.0.0",
                endpoint = "/api/video/info",
                site = "kemono",
                requestValue = "/file/test",
                requestedUrl = "https://kemono.cr/data/file.mp4",
                sourceUrl = "https://kemono.cr/data/file.mp4",
                stage = "HEAD:primary",
                statusCode = 502,
                errorMessage = "Remote source returned HTTP 502",
                retary = 0,
                createdAt = Instant.parse("2026-03-11T11:00:00Z"),
            ),
        )

        val rows = repository.findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, 10))
        assertEquals(1, rows.size)
        assertEquals(2, rows.first().requests)
        assertEquals(Instant.parse("2026-03-11T11:00:00Z"), rows.first().createdAt)
        assertEquals(
            "2026-03-11 11:00:00.000",
            jdbcTemplate.queryForObject("select created_at from source_error_log", String::class.java),
        )
    }

    @Test
    fun `normalizes legacy created_at values to sqlite format on startup`() {
        val dbFile = Files.createTempFile("source-error-repository-legacy", ".db")
        val jdbcTemplate = JdbcTemplate(sqliteDataSource(dbFile.absolutePathString()))
        jdbcTemplate.execute(
            """
            create table source_error_log (
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
            "1.0.0",
            "/api/video/info",
            "kemono",
            "/file/test",
            "https://kemono.cr/data/file.mp4",
            "https://kemono.cr/data/file.mp4",
            "HEAD:primary",
            502,
            "Remote source returned HTTP 502",
            0,
            1,
            "2026-03-11T11:00:00Z",
        )

        val repository = SourceErrorLogRepository(jdbcTemplate)

        val rows = repository.findAllByOrderByCreatedAtDesc(org.springframework.data.domain.PageRequest.of(0, 10))
        assertEquals(1, rows.size)
        assertEquals(Instant.parse("2026-03-11T11:00:00Z"), rows.first().createdAt)
        assertEquals(
            "2026-03-11 11:00:00.000",
            jdbcTemplate.queryForObject("select created_at from source_error_log", String::class.java),
        )
    }

    private fun sqliteDataSource(absolutePath: String): DriverManagerDataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:$absolutePath"
        }
}
