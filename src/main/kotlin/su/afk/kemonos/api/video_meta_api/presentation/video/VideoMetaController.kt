package su.afk.kemonos.api.video_meta_api.presentation.video

import jakarta.validation.Valid
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import su.afk.kemonos.api.video_meta_api.application.video.VideoMetaService
import su.afk.kemonos.api.video_meta_api.config.ClientIpResolver
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.VideoMetaRemoveRequest
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.VideoMetaRemoveResponse
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.VideoMetaRequest
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.VideoMetaResponse
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.toDomain
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.toDto

/**
 * Контроллер для операций с видео:
 * получение метаданных с превью и удаление записи.
 */
@RestController
@RequestMapping("/api/video")
class VideoMetaController(
    private val videoMetaService: VideoMetaService,
    private val clientIpResolver: ClientIpResolver,
) {
    /**
     * Возвращает информацию о видео и при необходимости инициирует генерацию превью.
     */
    @PostMapping("/info")
    fun videoInfo(
        @RequestHeader("Kemonos") clientVersion: String,
        @Valid @RequestBody request: VideoMetaRequest,
        httpRequest: HttpServletRequest,
    ): VideoMetaResponse = videoMetaService.videoInfo(
        request = request.toDomain(),
        clientVersion = clientVersion,
        clientKey = clientIpResolver.resolve(httpRequest),
    ).toDto()

    /**
     * Удаляет метаданные и превью видео.
     */
    @DeleteMapping("/remove")
    fun remove(
        @RequestHeader("Kemonos") clientVersion: String,
        @Valid @RequestBody request: VideoMetaRemoveRequest,
    ): VideoMetaRemoveResponse {
        @Suppress("UNUSED_VARIABLE")
        val ignored = clientVersion
        return videoMetaService.removeVideo(request.toDomain()).toDto()
    }
}
