package su.afk.kemonos.api.video_meta_api.application.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.SourceErrorLogEntity
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.SourceErrorLogRepository
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.absolutePathString

class SourceErrorRetrySchedulerTest {

    @Test
    fun `retries oldest records and increments retary on failure`() {
        val repository = SourceErrorLogRepository(
            JdbcTemplate(sqliteDataSource(Files.createTempFile("retry-scheduler", ".db").absolutePathString())),
        )
        val oldest = repository.save(
            SourceErrorLogEntity(
                clientVersion = "1.0.0",
                endpoint = "/api/video/info",
                requestedUrl = "https://kemono.cr/data/a/b/file.mp4",
                stage = "request",
                statusCode = 502,
                createdAt = Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )
        repository.save(
            SourceErrorLogEntity(
                clientVersion = "1.0.0",
                endpoint = "/api/video/info",
                requestedUrl = "https://kemono.cr/data/c/d/file.mp4",
                stage = "request",
                statusCode = 502,
                createdAt = Instant.parse("2026-03-11T11:00:00Z"),
            ),
        )

        val scheduler = SourceErrorRetryScheduler(
            sourceErrorLogRepository = repository,
            sourceErrorRetryService = object : SourceErrorRetryService {
                override fun retrySourceError(errorLog: SourceErrorLogEntity): Boolean {
                    throw IllegalStateException("still failing for ${errorLog.requestedUrl}")
                }
            },
            batchSize = 1,
            maxRetary = 3,
        )

        scheduler.retryOldestErrors()

        val rows = repository.findAllByOrderByCreatedAtAsc(PageRequest.of(0, 10))
        val retried = rows.first { it.requestedUrl == oldest.requestedUrl }
        assertEquals(1, retried.retary)
        assertEquals(2, rows.size)
    }

    @Test
    fun `deletes row after successful retry`() {
        val repository = SourceErrorLogRepository(
            JdbcTemplate(sqliteDataSource(Files.createTempFile("retry-success", ".db").absolutePathString())),
        )
        repository.save(
            SourceErrorLogEntity(
                clientVersion = "1.0.0",
                endpoint = "/api/file/info",
                requestedUrl = "https://kemono.cr/data/a/b/file.mp3",
                stage = "request",
                statusCode = 502,
                createdAt = Instant.parse("2026-03-11T10:00:00Z"),
            ),
        )

        val scheduler = SourceErrorRetryScheduler(
            sourceErrorLogRepository = repository,
            sourceErrorRetryService = object : SourceErrorRetryService {
                override fun retrySourceError(errorLog: SourceErrorLogEntity): Boolean = true
            },
            batchSize = 1,
            maxRetary = 3,
        )

        scheduler.retryOldestErrors()

        assertEquals(0, repository.count())
    }

    private fun sqliteDataSource(absolutePath: String): DriverManagerDataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:$absolutePath"
        }
}
