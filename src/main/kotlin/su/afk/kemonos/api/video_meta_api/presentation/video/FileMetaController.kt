package su.afk.kemonos.api.video_meta_api.presentation.video

import jakarta.validation.Valid
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import su.afk.kemonos.api.video_meta_api.application.video.VideoMetaService
import su.afk.kemonos.api.video_meta_api.config.ClientIpResolver
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.VideoMetaRequest
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.VideoMetaResponse
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.toDomain
import su.afk.kemonos.api.video_meta_api.presentation.video.dto.toDto

/**
 * Контроллер для получения базовой информации о файле без генерации превью.
 */
@RestController
@RequestMapping("/api/file")
class FileMetaController(
    private val videoMetaService: VideoMetaService,
    private val clientIpResolver: ClientIpResolver,
) {
    /**
     * Возвращает метаданные файла: размер, длительность и статус доступности.
     */
    @PostMapping("/info")
    fun fileInfo(
        @RequestHeader("Kemonos") clientVersion: String,
        @Valid @RequestBody request: VideoMetaRequest,
        httpRequest: HttpServletRequest,
    ): VideoMetaResponse = videoMetaService.fileInfo(
        request = request.toDomain(),
        clientVersion = clientVersion,
        clientKey = clientIpResolver.resolve(httpRequest),
    ).toDto()
}
