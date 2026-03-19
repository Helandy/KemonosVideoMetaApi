package su.afk.kemonos.api.video_meta_api.infrastructure.thumbnail

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Генерирует и удаляет миниатюры видео в формате WebP.
 */
@Service
class ThumbnailGenerationService(
    @Value("\${app.thumbnail.root:/data/thumbnail}")
    thumbnailRootPath: String,
    @Value("\${app.thumbnail.generation-timeout-seconds:30}")
    thumbnailGenerationTimeoutSeconds: Long,
    @Value("\${app.thumbnail.max-concurrent-generations:\${app.source.max-concurrent-requests:4}}")
    maxConcurrentGenerations: Int,
    @Value("\${app.thumbnail.ffmpeg-threads:2}")
    ffmpegThreads: Int,
    @Value("\${app.thumbnail.scale-flags:bicublin}")
    scaleFlags: String,
    @Value("\${app.thumbnail.webp-compression-level:4}")
    webpCompressionLevel: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val shortVideoSingleThumbnailMaxDurationSeconds: Long = 61
    private val sourceFrameExtractionAttempts: Int = 3
    private val thumbnailRoot: Path = Path.of(thumbnailRootPath)
    private val thumbnailMaxBytes: Long = 30 * 1024
    private val thumbnailGenerationTimeoutMillis: Long =
        TimeUnit.SECONDS.toMillis(thumbnailGenerationTimeoutSeconds.coerceAtLeast(1))
    private val thumbnailVisibilityWaitMillis: Long = 1_000
    private val thumbnailVisibilityPollMillis: Long = 50
    private val thumbnailHeightSteps = listOf(720, 640, 560, 480, 420, 360, 320, 280, 240, 200, 180)
    private val thumbnailQualitySteps = listOf(70, 60, 50, 40, 30)
    private val inFlightGeneration = ConcurrentHashMap<String, CompletableFuture<ThumbnailMeta>>()
    // Лимитирует число одновременно идущих pipeline генерации превью.
    private val generationSemaphore = Semaphore(maxConcurrentGenerations.coerceAtLeast(1), true)
    // Ограничивает число worker threads, которые ffmpeg может занять внутри одного процесса.
    private val ffmpegThreadCount = ffmpegThreads.coerceAtLeast(1)
    // Уровень сжатия WebP: ниже значение быстрее, выше значение экономит размер ценой CPU.
    private val webpCompression = webpCompressionLevel.coerceIn(0, 6)
    // Алгоритм ресайза кадра перед кодированием; bicublin даёт более аккуратный компромисс между скоростью и качеством.
    private val scaleAlgorithm = scaleFlags.trim().ifBlank { "bicublin" }

    /**
     * Удаляет директорию миниатюр, связанную с указанным request-путём.
     */
    fun deleteThumbnailsByRequest(requestValue: String): Boolean {
        val requestWithoutSlash = requestValue.removePrefix("/")
        if (requestWithoutSlash.isBlank()) return false
        val dir = thumbnailRoot.resolve(requestWithoutSlash)
        if (!Files.exists(dir)) return false
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        return true
    }

    /**
     * Синхронно генерирует 3 миниатюры (25/50/75% таймлайна) с защитой от дублей в параллели.
     */
    fun generateBlocking(requestValue: String, sourceUrl: String, durationSeconds: Long, statusCode: Int): ThumbnailMeta {
        if (statusCode !in 200..299 || durationSeconds <= 0) {
            return ThumbnailMeta(ready = false)
        }
        val requestWithoutSlash = requestValue.removePrefix("/")
        if (requestWithoutSlash.isBlank()) {
            return ThumbnailMeta(ready = false)
        }

        val currentFuture = CompletableFuture<ThumbnailMeta>()
        val inFlight = inFlightGeneration.putIfAbsent(requestWithoutSlash, currentFuture)
        if (inFlight != null) return joinThumbnailFuture(inFlight)

        try {
            val result = withGenerationPermit {
                val deadlineMs = System.currentTimeMillis() + thumbnailGenerationTimeoutMillis
                val dir = thumbnailRoot.resolve(requestWithoutSlash)
                Files.createDirectories(dir)
                ensureSingleThumbnail(sourceUrl, durationSeconds, 25, dir.resolve("25.webp"), deadlineMs)
                if (durationSeconds < shortVideoSingleThumbnailMaxDurationSeconds) {
                    ensureAliasThumbnail(
                        sourceFile = dir.resolve("25.webp"),
                        aliasFile = dir.resolve("50.webp"),
                    )
                    ensureAliasThumbnail(
                        sourceFile = dir.resolve("25.webp"),
                        aliasFile = dir.resolve("75.webp"),
                    )
                } else {
                    ensureSingleThumbnail(sourceUrl, durationSeconds, 50, dir.resolve("50.webp"), deadlineMs)
                    ensureSingleThumbnail(sourceUrl, durationSeconds, 75, dir.resolve("75.webp"), deadlineMs)
                }
                waitForVisibleThumbnails(
                    files = listOf(
                        dir.resolve("25.webp"),
                        dir.resolve("50.webp"),
                        dir.resolve("75.webp"),
                    ),
                    deadlineMs = minOf(deadlineMs, System.currentTimeMillis() + thumbnailVisibilityWaitMillis),
                )
                ThumbnailMeta(ready = true)
            }
            currentFuture.complete(result)
            return result
        } catch (ex: Exception) {
            currentFuture.completeExceptionally(ex)
            logger.warn("Thumbnail generation failed for request={} url={}: {}", requestValue, sourceUrl, ex.message)
            throw ex
        } finally {
            inFlightGeneration.remove(requestWithoutSlash, currentFuture)
        }
    }

    /**
     * Ограничивает число одновременно работающих `ffmpeg`-pipeline на генерацию превью.
     */
    private fun <T> withGenerationPermit(block: () -> T): T {
        try {
            generationSemaphore.acquire()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Interrupted while waiting for thumbnail generation slot", ex)
        }
        try {
            return block()
        } finally {
            generationSemaphore.release()
        }
    }

    /**
     * Коротко дожидается, пока все превью станут видимыми на файловой системе.
     */
    private fun waitForVisibleThumbnails(files: List<Path>, deadlineMs: Long) {
        while (true) {
            val ready = files.all { path ->
                Files.exists(path) && runCatching { Files.size(path) > 0 }.getOrDefault(false)
            }
            if (ready) return
            if (System.currentTimeMillis() >= deadlineMs) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Generated thumbnails are not visible yet")
            }
            Thread.sleep(thumbnailVisibilityPollMillis)
        }
    }

    /**
     * Для коротких видео создаёт 50/75 как жёсткие ссылки на 25.webp, чтобы не хранить дубль.
     */
    private fun ensureAliasThumbnail(sourceFile: Path, aliasFile: Path) {
        if (!Files.exists(sourceFile) || Files.size(sourceFile) <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Source thumbnail is missing")
        }

        if (Files.exists(aliasFile)) {
            if (runCatching { Files.isSameFile(sourceFile, aliasFile) }.getOrDefault(false)) {
                return
            }
            Files.delete(aliasFile)
        }

        try {
            Files.createLink(aliasFile, sourceFile)
        } catch (ex: UnsupportedOperationException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Hard links are not supported for thumbnail aliasing", ex)
        } catch (ex: IOException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not create hard link for thumbnail alias", ex)
        }
    }

    /**
     * Ожидает результат уже запущенной генерации и пробрасывает исходную ошибку.
     */
    private fun joinThumbnailFuture(future: CompletableFuture<ThumbnailMeta>): ThumbnailMeta {
        return try {
            future.join()
        } catch (ex: CompletionException) {
            val cause = ex.cause
            if (cause is RuntimeException) throw cause
            throw ex
        }
    }

    /**
     * Генерирует один thumbnail:
     * извлекает исходный кадр и подбирает параметры сжатия до целевого размера.
     */
    private fun ensureSingleThumbnail(
        sourceUrl: String,
        durationSeconds: Long,
        percent: Int,
        targetFile: Path,
        deadlineMs: Long,
    ) {
        if (Files.exists(targetFile) && Files.size(targetFile) in 1..thumbnailMaxBytes) return

        val seconds = durationSeconds * (percent / 100.0)
        val sourceFrame = Files.createTempFile("thumb-source-$percent-", ".png")
        var produced = false

        try {
            extractSourceFrameWithRetry(
                sourceUrl = sourceUrl,
                second = seconds,
                sourceFrame = sourceFrame,
                deadlineMs = deadlineMs,
            )

            for (height in thumbnailHeightSteps) {
                for (quality in thumbnailQualitySteps) {
                    val remainingMs = deadlineMs - System.currentTimeMillis()
                    if (remainingMs <= 0) throw generationTimeoutException()
                    Files.deleteIfExists(targetFile)
                    val ok = runFfmpegCompressLocalFrame(
                        sourceFrame = sourceFrame,
                        height = height,
                        quality = quality,
                        targetFile = targetFile,
                        maxWaitMillis = remainingMs,
                    )
                    if (!ok || !Files.exists(targetFile)) continue
                    produced = true
                    val size = Files.size(targetFile)
                    if (size in 1..thumbnailMaxBytes) return
                }
            }
        } finally {
            Files.deleteIfExists(sourceFrame)
        }

        val message = if (produced && Files.exists(targetFile)) {
            "Could not compress thumbnail ${targetFile.fileName} to <= ${thumbnailMaxBytes / 1024}KB"
        } else {
            "Could not generate thumbnail ${targetFile.fileName}"
        }
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, message)
    }

    /**
     * Извлекает кадр из видео через ffmpeg с несколькими попытками.
     */
    private fun extractSourceFrameWithRetry(
        sourceUrl: String,
        second: Double,
        sourceFrame: Path,
        deadlineMs: Long,
    ) {
        var lastError: Exception? = null
        repeat(sourceFrameExtractionAttempts) {
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0) throw generationTimeoutException()
            runCatching {
                Files.deleteIfExists(sourceFrame)
                val ok = runFfmpegExtractSourceFrame(
                    sourceUrl = sourceUrl,
                    second = second,
                    targetFile = sourceFrame,
                    maxWaitMillis = remainingMs,
                )
                if (!ok || !Files.exists(sourceFrame)) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not extract source frame")
                }
            }.onSuccess {
                return
            }.onFailure { ex ->
                lastError = if (ex is Exception) ex else RuntimeException(ex)
            }
        }
        throw lastError ?: ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not extract source frame")
    }

    /**
     * Запускает ffmpeg для извлечения одного PNG-кадра из удалённого видео.
     */
    private fun runFfmpegExtractSourceFrame(
        sourceUrl: String,
        second: Double,
        targetFile: Path,
        maxWaitMillis: Long,
    ): Boolean {
        val process = try {
            ProcessBuilder(
                "ffmpeg",
                "-y",
                "-v", "error",
                "-threads", ffmpegThreadCount.toString(),
                "-ss", String.format(Locale.US, "%.3f", second),
                "-i", sourceUrl,
                "-an",
                "-sn",
                "-dn",
                "-frames:v", "1",
                "-vsync", "vfr",
                targetFile.toString(),
            ).start()
        } catch (ex: IOException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "ffmpeg is required to generate thumbnails (install ffmpeg)",
                ex,
            )
        }

        if (!process.waitFor(maxWaitMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw generationTimeoutException()
        }
        process.inputStream.use { it.readAllBytes() }
        process.errorStream.use { it.readAllBytes() }
        return process.exitValue() == 0
    }

    /**
     * Запускает ffmpeg для сжатия локального кадра в WebP с ограничением по высоте.
     */
    private fun runFfmpegCompressLocalFrame(
        sourceFrame: Path,
        height: Int,
        quality: Int,
        targetFile: Path,
        maxWaitMillis: Long,
    ): Boolean {
        val process = try {
            ProcessBuilder(
                "ffmpeg",
                "-y",
                "-v", "error",
                "-threads", ffmpegThreadCount.toString(),
                "-i", sourceFrame.toString(),
                "-frames:v", "1",
                "-vf", "scale=-2:min($height\\,ih):flags=$scaleAlgorithm",
                "-c:v", "libwebp",
                "-compression_level", webpCompression.toString(),
                "-preset", "picture",
                "-q:v", quality.toString(),
                targetFile.toString(),
            ).start()
        } catch (ex: IOException) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "ffmpeg is required to generate thumbnails (install ffmpeg)",
                ex,
            )
        }

        if (!process.waitFor(maxWaitMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw generationTimeoutException()
        }
        process.inputStream.use { it.readAllBytes() }
        process.errorStream.use { it.readAllBytes() }
        return process.exitValue() == 0
    }

    /**
     * Формирует стандартное исключение о превышении времени генерации.
     */
    private fun generationTimeoutException(): ResponseStatusException =
        ResponseStatusException(
            HttpStatus.GATEWAY_TIMEOUT,
            "Thumbnail generation timeout after ${thumbnailGenerationTimeoutMillis / 1000}s; retry later",
        )
}

/**
 * Относительные пути к сгенерированным миниатюрам для трёх точек таймлайна.
 */
data class ThumbnailMeta(
    val ready: Boolean,
)
