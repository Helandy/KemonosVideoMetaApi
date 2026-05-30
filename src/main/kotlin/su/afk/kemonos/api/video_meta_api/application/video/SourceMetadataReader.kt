package su.afk.kemonos.api.video_meta_api.application.video

interface SourceMetadataReader {
    fun fetchRemoteMeta(url: String, siteRootUrl: String?, errorLogContext: SourceErrorLogContext): RemoteMeta

    fun resolveCachedSourceUrl(originalUrl: String): String = originalUrl
}

data class RemoteMeta(
    val sizeBytes: Long,
    val durationSeconds: Long,
    val statusCode: Int,
    val sourceUrl: String,
)
