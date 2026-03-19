package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.repository

import org.springframework.data.jpa.repository.JpaRepository
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity.VideoMetaEntity
import java.util.Optional

/**
 * Репозиторий доступа к таблице метаданных видео.
 */
interface VideoMetaRepository : JpaRepository<VideoMetaEntity, Long> {
    /**
     * Ищет запись по полному нормализованному URL источника.
     */
    fun findByResolvedUrl(resolvedUrl: String): Optional<VideoMetaEntity>

    /**
     * Ищет запись по сайту, request-пути и типу медиа.
     */
    fun findBySiteAndRequestAndMediaType(site: String, request: String, mediaType: String): Optional<VideoMetaEntity>
}
