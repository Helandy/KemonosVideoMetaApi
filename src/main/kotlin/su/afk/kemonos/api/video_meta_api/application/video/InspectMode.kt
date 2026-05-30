package su.afk.kemonos.api.video_meta_api.application.video

import su.afk.kemonos.api.video_meta_api.infrastructure.source.InspectRequestType

enum class InspectMode {
    MEDIA,
    VIDEO;

    val storageValue: String
        get() = when (this) {
            MEDIA -> "audio"
            VIDEO -> "video"
        }

    val requestType: InspectRequestType
        get() = when (this) {
            MEDIA -> InspectRequestType.FILE_INFO
            VIDEO -> InspectRequestType.VIDEO_INFO
        }

    val endpoint: String
        get() = when (this) {
            MEDIA -> "/api/file/info"
            VIDEO -> "/api/video/info"
        }
}
