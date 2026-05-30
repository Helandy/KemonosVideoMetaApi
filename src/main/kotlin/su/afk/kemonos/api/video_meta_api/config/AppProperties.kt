package su.afk.kemonos.api.video_meta_api.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.source")
class SourceProperties {
    var maxConcurrentRequests: Int = 4
    var allowedHostSuffixes: List<String> = listOf("coomer.st", "coomer.su", "kemono.su", "kemono.cr")
    var allowedAudioExtensions: Set<String> = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "wma")
    var allowedVideoExtensions: Set<String> = setOf(
        "mp4", "m4v", "mov", "webm", "avi",
        "wmv", "flv", "mpeg", "mpg", "3gp", "mkv",
    )
}

@ConfigurationProperties("app.inspect")
class InspectProperties {
    var maxConcurrentRequestsPerEndpoint: Int = 4
    var maxQueuedRequestsPerUser: Int = 60
    var maxQueueWaitSeconds: Long = 30
    var file: EndpointConcurrencyProperties = EndpointConcurrencyProperties()
    var video: EndpointConcurrencyProperties = EndpointConcurrencyProperties(defaultMaxConcurrentRequests = 6)
}

@ConfigurationProperties("app.rate-limit")
class RateLimitProperties {
    var maxRequests: Int = 60
    var windowSeconds: Long = 10
}

class EndpointConcurrencyProperties(
    defaultMaxConcurrentRequests: Int = 4,
) {
    var maxConcurrentRequests: Int = defaultMaxConcurrentRequests
}

@ConfigurationProperties("app.thumbnail")
class ThumbnailProperties {
    var root: String = "/data/thumbnail"
    var generationTimeoutSeconds: Long = 30
    var maxConcurrentGenerations: Int = 4
    var ffmpegThreads: Int = 2
    var scaleFlags: String = "area"
    var webpCompressionLevel: Int = 4
}

@ConfigurationProperties("app.admin")
class AdminProperties {
    var key: AdminKeyProperties = AdminKeyProperties()
    var maxFailedAttempts: Int = 10
    var banMinutes: Long = 60
}

class AdminKeyProperties {
    var path: String = "/data/.admin.key"
}

@ConfigurationProperties("app.source-error-log.retry")
class SourceErrorRetryProperties {
    var batchSize: Int = 25
    var maxRetry: Int = 3
}
