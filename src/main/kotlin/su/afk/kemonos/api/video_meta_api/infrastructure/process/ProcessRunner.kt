package su.afk.kemonos.api.video_meta_api.infrastructure.process

import org.springframework.stereotype.Component
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Component
class ProcessRunner {
    fun run(command: List<String>, timeout: Duration, outputLimitBytes: Int = 16 * 1024): ProcessResult {
        val process = ProcessBuilder(command).start()
        val stdout = CompletableFuture.supplyAsync {
            process.inputStream.use { it.readTextLimited(outputLimitBytes) }
        }
        val stderr = CompletableFuture.supplyAsync {
            process.errorStream.use { it.readTextLimited(outputLimitBytes) }
        }

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

    private fun CompletableFuture<String>.getCompletedOrEmpty(): String =
        runCatching { get(1, TimeUnit.SECONDS) }.getOrDefault("")

    private fun InputStream.readTextLimited(limitBytes: Int): String {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArray(limitBytes.coerceAtLeast(1))
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
}

data class ProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)
