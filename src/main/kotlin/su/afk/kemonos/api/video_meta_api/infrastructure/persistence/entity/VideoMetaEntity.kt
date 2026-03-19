package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.converter.SqliteInstantConverter
import java.time.Instant

/**
 * Сущность метаданных видео, сохраняемая в таблице `video_meta`.
 */
@Entity
@Table(
    name = "video_meta",
    indexes = [
        Index(name = "idx_video_meta_site_request_media_type", columnList = "site, request, media_type"),
    ],
)
class VideoMetaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var site: String? = null,
    var server: String? = null,
    @Column(nullable = false)
    var request: String = "",
    var ext: String? = null,
    @Column(nullable = false, unique = true)
    var resolvedUrl: String = "",
    @Column(nullable = false)
    var sizeBytes: Long = 0,
    var durationSeconds: Long? = null,
    var lastStatusCode: Int? = null,
    @Column(nullable = false)
    var mediaType: String = "video",
    @Column(nullable = false)
    var thumbnailsReady: Boolean = false,
    @Column(nullable = false)
    @Convert(converter = SqliteInstantConverter::class)
    var createdAt: Instant = Instant.now(),
)
