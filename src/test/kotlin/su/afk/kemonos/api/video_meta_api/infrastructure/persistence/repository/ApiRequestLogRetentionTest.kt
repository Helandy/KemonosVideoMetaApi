package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.ApiRequestLogEntity
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.absolutePathString

class ApiRequestLogRetentionTest {

    @Test
    fun `deletes only rows older than the threshold`() {
        val jdbcTemplate = JdbcTemplate(
            DriverManagerDataSource().apply {
                setDriverClassName("org.sqlite.JDBC")
                url = "jdbc:sqlite:${Files.createTempFile("api-request-retention", ".db").absolutePathString()}"
            },
        )
        val repository = ApiRequestLogRepository(jdbcTemplate)

        listOf(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-05-01T00:00:00Z"),
            Instant.parse("2026-07-01T00:00:00Z"),
        ).forEach { createdAt ->
            repository.save(
                ApiRequestLogEntity(
                    clientVersion = "1.0.0",
                    endpoint = "/api/video/info",
                    requestValue = "/file/test",
                    resolvedUrl = "https://kemono.cr/data/file.mp4",
                    createdAt = createdAt,
                ),
            )
        }

        val removed = repository.deleteOlderThan(Instant.parse("2026-06-01T00:00:00Z"))

        assertEquals(2, removed)
        assertEquals(
            1,
            jdbcTemplate.queryForObject("select count(*) from api_request_log", Int::class.java),
        )
    }
}
