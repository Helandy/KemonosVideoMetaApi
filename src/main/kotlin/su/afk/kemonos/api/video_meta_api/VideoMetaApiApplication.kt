package su.afk.kemonos.api.video_meta_api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Точка конфигурации Spring Boot приложения.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class VideoMetaApiApplication

/**
 * Запускает приложение Video Meta API.
 */
fun main(args: Array<String>) {
	runApplication<VideoMetaApiApplication>(*args)
}
