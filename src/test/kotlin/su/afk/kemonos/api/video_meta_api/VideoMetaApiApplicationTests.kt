package su.afk.kemonos.api.video_meta_api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

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

	@Test
	/**
	 * Проверяет, что контекст приложения успешно поднимается.
	 */
	fun contextLoads() {
	}

}
