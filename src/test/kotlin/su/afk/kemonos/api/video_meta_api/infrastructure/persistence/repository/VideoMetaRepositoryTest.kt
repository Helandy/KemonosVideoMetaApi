package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.VideoMetaEntity
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.absolutePathString

class VideoMetaRepositoryTest {

    @Test
    fun `inserts and reads back all fields`() {
        val jdbcTemplate = newJdbcTemplate("video-meta-insert")
        val repository = VideoMetaRepository(jdbcTemplate)

        val saved = repository.save(
            VideoMetaEntity(
                site = "kemono",
                server = "https://kemono.cr",
                request = "/data/a/b/file.mp4",
                ext = "mp4",
                resolvedUrl = "https://kemono.cr/data/a/b/file.mp4",
                sizeBytes = 123_456,
                durationSeconds = 620,
                lastStatusCode = 200,
                mediaType = "video",
                thumbnailsReady = true,
                createdAt = Instant.parse("2026-03-11T11:00:00Z"),
            ),
        )

        assertNotNull(saved.id)

        val loaded = repository.findByResolvedUrl("https://kemono.cr/data/a/b/file.mp4")
        assertNotNull(loaded)
        requireNotNull(loaded)
        assertEquals(saved.id, loaded.id)
        assertEquals("kemono", loaded.site)
        assertEquals("https://kemono.cr", loaded.server)
        assertEquals("/data/a/b/file.mp4", loaded.request)
        assertEquals("mp4", loaded.ext)
        assertEquals(123_456, loaded.sizeBytes)
        assertEquals(620, loaded.durationSeconds)
        assertEquals(200, loaded.lastStatusCode)
        assertEquals("video", loaded.mediaType)
        assertTrue(loaded.thumbnailsReady)
        assertEquals(Instant.parse("2026-03-11T11:00:00Z"), loaded.createdAt)

        assertEquals(
            "2026-03-11 11:00:00.000",
            jdbcTemplate.queryForObject("select created_at from video_meta", String::class.java),
        )
    }

    @Test
    fun `keeps null values null`() {
        val repository = VideoMetaRepository(newJdbcTemplate("video-meta-nulls"))

        repository.save(
            VideoMetaEntity(
                request = "/data/a/b/file.mp3",
                resolvedUrl = "https://kemono.cr/data/a/b/file.mp3",
                sizeBytes = 10,
                mediaType = "audio",
            ),
        )

        val loaded = requireNotNull(repository.findByResolvedUrl("https://kemono.cr/data/a/b/file.mp3"))
        assertNull(loaded.site)
        assertNull(loaded.server)
        assertNull(loaded.ext)
        assertNull(loaded.durationSeconds)
        assertNull(loaded.lastStatusCode)
    }

    @Test
    fun `updates existing row instead of inserting a duplicate`() {
        val jdbcTemplate = newJdbcTemplate("video-meta-update")
        val repository = VideoMetaRepository(jdbcTemplate)

        val saved = repository.save(
            VideoMetaEntity(
                site = "kemono",
                request = "/data/a/b/file.mp4",
                resolvedUrl = "https://kemono.cr/data/a/b/file.mp4",
                sizeBytes = 1,
                mediaType = "video",
            ),
        )

        saved.sizeBytes = 999
        saved.thumbnailsReady = true
        repository.save(saved)

        assertEquals(
            1,
            jdbcTemplate.queryForObject("select count(*) from video_meta", Int::class.java),
        )
        val loaded = requireNotNull(repository.findByResolvedUrl("https://kemono.cr/data/a/b/file.mp4"))
        assertEquals(999, loaded.sizeBytes)
        assertTrue(loaded.thumbnailsReady)
    }

    @Test
    fun `finds by site request and media type`() {
        val repository = VideoMetaRepository(newJdbcTemplate("video-meta-lookup"))

        repository.save(
            VideoMetaEntity(
                site = "coomer",
                request = "/data/x/y/clip.mp4",
                resolvedUrl = "https://coomer.st/data/x/y/clip.mp4",
                sizeBytes = 5,
                mediaType = "video",
            ),
        )

        assertNotNull(repository.findBySiteAndRequestAndMediaType("coomer", "/data/x/y/clip.mp4", "video"))
        assertNull(repository.findBySiteAndRequestAndMediaType("coomer", "/data/x/y/clip.mp4", "audio"))
    }

    @Test
    fun `deletes row`() {
        val repository = VideoMetaRepository(newJdbcTemplate("video-meta-delete"))

        val saved = repository.save(
            VideoMetaEntity(
                request = "/data/a/b/file.mp4",
                resolvedUrl = "https://kemono.cr/data/a/b/file.mp4",
                sizeBytes = 1,
                mediaType = "video",
            ),
        )

        repository.delete(saved)

        assertNull(repository.findByResolvedUrl("https://kemono.cr/data/a/b/file.mp4"))
    }

    private fun newJdbcTemplate(prefix: String): JdbcTemplate =
        JdbcTemplate(
            DriverManagerDataSource().apply {
                setDriverClassName("org.sqlite.JDBC")
                url = "jdbc:sqlite:${Files.createTempFile(prefix, ".db").absolutePathString()}"
            },
        )
}
