package su.afk.kemonos.api.video_meta_api.infrastructure.process

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.io.InputStream
import java.lang.ProcessBuilder.Redirect
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Запускает внешние процессы, читая их вывод на виртуальных потоках.
 *
 * Блокирующее чтение не отдаётся в `ForkJoinPool.commonPool`, чтобы пул не наращивал
 * компенсирующие платформенные потоки, а буферы вывода держатся минимальными.
 */
@Component
class ProcessRunner {
    private val outputReaders: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Выполняет команду и возвращает её результат.
     *
     * @param captureStdout читать ли stdout; если он не нужен, поток перенаправляется в `/dev/null`
     * и не занимает ни памяти, ни отдельного читателя.
     * @param outputLimitBytes верхняя граница на каждый захватываемый поток.
     */
    fun run(
        command: List<String>,
        timeout: Duration,
        captureStdout: Boolean = true,
        outputLimitBytes: Int = DEFAULT_OUTPUT_LIMIT_BYTES,
    ): ProcessResult {
        val builder = ProcessBuilder(command)
            .redirectInput(Redirect.from(NULL_FILE))
        if (!captureStdout) {
            builder.redirectOutput(Redirect.DISCARD)
        }

        val process = builder.start()
        val stdout = if (captureStdout) {
            outputReaders.submitRead(process.inputStream, outputLimitBytes)
        } else {
            CompletableFuture.completedFuture("")
        }
        val stderr = outputReaders.submitRead(process.errorStream, outputLimitBytes)

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            return ProcessResult(
                exitCode = null,
                stdout = stdout.getCompletedOrEmpty(),
                stderr = stderr.getCompletedOrEmpty(),
                timedOut = true,
            )
        }

        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout.getCompletedOrEmpty(),
            stderr = stderr.getCompletedOrEmpty(),
            timedOut = false,
        )
    }

    @PreDestroy
    fun shutdown() {
        outputReaders.shutdownNow()
    }

    private fun ExecutorService.submitRead(stream: InputStream, limitBytes: Int): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        execute {
            val text = runCatching { stream.use { it.readTextLimited(limitBytes) } }.getOrDefault("")
            future.complete(text)
        }
        return future
    }

    private fun CompletableFuture<String>.getCompletedOrEmpty(): String =
        runCatching { get(1, TimeUnit.SECONDS) }.getOrDefault("")

    /**
     * Читает поток целиком, но удерживает в памяти не более `limitBytes` первых байт.
     */
    private fun InputStream.readTextLimited(limitBytes: Int): String {
        val limit = limitBytes.coerceAtLeast(1)
        val buffer = ByteArray(minOf(limit, DEFAULT_BUFFER_SIZE))
        val output = ByteArray(limit)
        var stored = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            val copyBytes = minOf(read, output.size - stored)
            if (copyBytes > 0) {
                buffer.copyInto(output, destinationOffset = stored, endIndex = copyBytes)
                stored += copyBytes
            }
        }
        return output.decodeToString(endIndex = stored)
    }

    private companion object {
        /**
         * Внешние утилиты запускаются с `-v error`, поэтому их вывод укладывается в пару килобайт.
         */
        const val DEFAULT_OUTPUT_LIMIT_BYTES = 2 * 1024
        val NULL_FILE = java.io.File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
    }
}

data class ProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)
