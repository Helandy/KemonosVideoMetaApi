package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.ApiRequestLogEntity
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.absolutePathString

class ApiRequestLogRepositoryTest {

    @Test
    fun `stores created_at in canonical sqlite format`() {
        val dbFile = Files.createTempFile("api-request-repository", ".db")
        val jdbcTemplate = JdbcTemplate(sqliteDataSource(dbFile.absolutePathString()))
        val repository = ApiRequestLogRepository(jdbcTemplate)

        repository.save(
            ApiRequestLogEntity(
                clientVersion = "1.0.0",
                endpoint = "/api/video/info",
                requestValue = "/file/test",
                resolvedUrl = "https://kemono.cr/data/file.mp4",
                createdAt = Instant.parse("2026-03-11T11:00:00Z"),
            ),
        )

        assertEquals(
            "2026-03-11 11:00:00.000",
            jdbcTemplate.queryForObject("select created_at from api_request_log", String::class.java),
        )
    }

    @Test
    fun `normalizes legacy created_at values to sqlite format on startup`() {
        val dbFile = Files.createTempFile("api-request-repository-legacy", ".db")
        val jdbcTemplate = JdbcTemplate(sqliteDataSource(dbFile.absolutePathString()))
        jdbcTemplate.execute(
            """
            create table api_request_log (
                id integer primary key autoincrement,
                client_version text not null,
                endpoint text not null,
                request_value text,
                resolved_url text,
                created_at text not null
            )
            """.trimIndent(),
        )
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
            "1.0.0",
            "/api/video/info",
            "/file/test",
            "https://kemono.cr/data/file.mp4",
            "2026-03-11T11:00:00Z",
        )

        ApiRequestLogRepository(jdbcTemplate)

        assertEquals(
            "2026-03-11 11:00:00.000",
            jdbcTemplate.queryForObject("select created_at from api_request_log", String::class.java),
        )
    }

    private fun sqliteDataSource(absolutePath: String): DriverManagerDataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:$absolutePath"
        }
}
