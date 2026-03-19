package su.afk.kemonos.api.video_meta_api.application.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VideoMetaServiceFallbackTest {

    @Test
    fun `builds kemono root fallback for subdomain host`() {
        val fallback = buildMirrorFallbackUrl(
            "https://n1.kemono.cr/data/ff/19/file.mp4?f=1",
            "https://kemono.cr",
        )

        assertEquals("https://kemono.cr/data/ff/19/file.mp4?f=1", fallback)
    }

    @Test
    fun `builds coomer root fallback for subdomain host`() {
        val fallback = buildMirrorFallbackUrl(
            "https://c2.coomer.st/data/aa/bb/file.mp4",
            "https://coomer.st",
        )

        assertEquals("https://coomer.st/data/aa/bb/file.mp4", fallback)
    }

    @Test
    fun `does not build fallback for root host`() {
        val fallback = buildMirrorFallbackUrl(
            "https://kemono.cr/data/ff/19/file.mp4",
            "https://kemono.cr",
        )

        assertNull(fallback)
    }

    @Test
    fun `does not build fallback when root url is absent`() {
        val fallback = buildMirrorFallbackUrl(
            "https://n1.kemono.su/data/ff/19/file.mp4",
            null,
        )

        assertNull(fallback)
    }
}
