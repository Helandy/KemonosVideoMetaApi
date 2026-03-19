package su.afk.kemonos.api.video_meta_api.infrastructure.source

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore

/**
 * Тип inspect-запроса, для которого выделяется отдельный лимит параллелизма.
 */
enum class InspectRequestType {
    FILE_INFO,
    VIDEO_INFO,
}

/**
 * Ограничивает число одновременно создаваемых новых записей через `/api/file/info` и `/api/video/info`.
 * Для каждого endpoint используется свой независимый semaphore.
 */
@Service
class SourceRequestLimiter(
    @Value("\${app.inspect.file.max-concurrent-requests:\${app.inspect.max-concurrent-requests-per-endpoint:\${app.source.max-concurrent-requests:2}}}")
    fileMaxConcurrentRequests: Int,
    @Value("\${app.inspect.video.max-concurrent-requests:\${app.inspect.max-concurrent-requests-per-endpoint:\${app.source.max-concurrent-requests:2}}}")
    videoMaxConcurrentRequests: Int,
    @Value("\${app.inspect.max-queued-requests-per-user:60}")
    maxQueuedRequestsPerUser: Int,
) {
    private val maxQueuedPerUser = maxQueuedRequestsPerUser.coerceAtLeast(1)
    private val fileInfoQueue = EndpointQueue(fileMaxConcurrentRequests.coerceAtLeast(1))
    private val videoInfoQueue = EndpointQueue(videoMaxConcurrentRequests.coerceAtLeast(1))
    private val queuedRequestsByUser = HashMap<String, Int>()

    /**
     * Выполняет блок только после захвата слота лимитера для нужного endpoint.
     */
    fun <T> withPermit(type: InspectRequestType, userKey: String, block: () -> T): T {
        val normalizedUserKey = userKey.trim().ifBlank { "unknown" }.take(64)
        val permit = reservePermit(type, normalizedUserKey)
        try {
            permit.await()
            return block()
        } finally {
            releasePermit(type)
        }
    }

    private fun reservePermit(type: InspectRequestType, userKey: String): QueuePermit {
        val permit = QueuePermit(userKey)
        synchronized(this) {
            val queuedCount = queuedRequestsByUser[userKey] ?: 0
            if (queuedCount >= maxQueuedPerUser) {
                throw ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many queued inspect requests for this user",
                )
            }
            queueFor(type).enqueue(permit)
            queuedRequestsByUser[userKey] = queuedCount + 1
            dispatchAvailable()
        }
        return permit
    }

    private fun releasePermit(type: InspectRequestType) {
        synchronized(this) {
            queueFor(type).releaseActive()
            dispatchAvailable()
        }
    }

    private fun queueFor(type: InspectRequestType): EndpointQueue = when (type) {
        InspectRequestType.FILE_INFO -> fileInfoQueue
        InspectRequestType.VIDEO_INFO -> videoInfoQueue
    }

    private fun dispatchAvailable() {
        fileInfoQueue.dispatch()
        videoInfoQueue.dispatch()
    }

    private fun decrementQueuedCount(userKey: String) {
        val updated = (queuedRequestsByUser[userKey] ?: 1) - 1
        if (updated > 0) {
            queuedRequestsByUser[userKey] = updated
        } else {
            queuedRequestsByUser.remove(userKey)
        }
    }

    private inner class EndpointQueue(
        maxConcurrentRequests: Int,
    ) {
        private val semaphore = Semaphore(maxConcurrentRequests, true)
        private val queuedUsers = ArrayDeque<String>()
        private val queuedPermitsByUser = LinkedHashMap<String, ArrayDeque<QueuePermit>>()

        fun enqueue(permit: QueuePermit) {
            val userQueue = queuedPermitsByUser.getOrPut(permit.userKey) { ArrayDeque() }
            if (userQueue.isEmpty()) {
                queuedUsers.addLast(permit.userKey)
            }
            userQueue.addLast(permit)
        }

        fun releaseActive() {
            semaphore.release()
        }

        fun dispatch() {
            while (semaphore.tryAcquire()) {
                val nextPermit = pollNextPermit() ?: run {
                    semaphore.release()
                    return
                }
                decrementQueuedCount(nextPermit.userKey)
                nextPermit.grant()
            }
        }

        private fun pollNextPermit(): QueuePermit? {
            if (queuedUsers.isEmpty()) return null
            val userKey = queuedUsers.removeFirst()
            val userQueue = queuedPermitsByUser[userKey] ?: return null
            if (userQueue.isEmpty()) return null
            val permit = userQueue.removeFirst()
            if (userQueue.isEmpty()) {
                queuedPermitsByUser.remove(userKey)
            } else {
                queuedUsers.addLast(userKey)
            }
            return permit
        }
    }
}

private class QueuePermit(
    val userKey: String,
) {
    private val latch = CountDownLatch(1)

    fun grant() {
        latch.countDown()
    }

    fun await() {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }
}
