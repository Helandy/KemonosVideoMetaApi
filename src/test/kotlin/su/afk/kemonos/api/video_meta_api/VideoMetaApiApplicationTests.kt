package su.afk.kemonos.api.video_meta_api

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.scheduling.config.ScheduledTaskHolder
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.ApiRequestLogRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.SourceErrorLogRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository.VideoMetaRepository

@SpringBootTest(
    properties = [
        "SPRING_DATASOURCE_URL=jdbc:sqlite:/tmp/video-meta-test-main.db",
        "APP_STATISTICS_DATASOURCE_URL=jdbc:sqlite:/tmp/video-meta-test-statistics.db",
        "APP_SOURCE_ERROR_LOG_DATASOURCE_URL=jdbc:sqlite:/tmp/video-meta-test-errors.db",
        "APP_THUMBNAIL_ROOT=/tmp/video-meta-thumb",
        "APP_ADMIN_KEY_PATH=/tmp/.admin.key",
    ],
)
/**
 * Базовый smoke-тест запуска контекста Spring.
 */
class VideoMetaApiApplicationTests {

    /**
     * Репозитории инжектятся явно: при ленивой инициализации без этого
     * их схема в рамках теста не создавалась бы вовсе.
     */
    @Autowired
    private lateinit var videoMetaRepository: VideoMetaRepository

    @Autowired
    private lateinit var apiRequestLogRepository: ApiRequestLogRepository

    @Autowired
    private lateinit var sourceErrorLogRepository: SourceErrorLogRepository

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

	@Test
	/**
	 * Проверяет, что контекст приложения успешно поднимается,
	 * а схемы всех трёх баз создаются.
	 */
	fun contextLoads() {
        videoMetaRepository.findByResolvedUrl("https://kemono.cr/data/missing.mp4")
        apiRequestLogRepository.countRequestsByVersion()
        sourceErrorLogRepository.count()
	}

    @Test
    /**
     * Ленивая инициализация не должна мешать регистрации периодических задач.
     */
    fun `registers scheduled tasks despite lazy initialization`() {
        assertFalse(scheduledTaskHolder.scheduledTasks.isEmpty())
    }
}
