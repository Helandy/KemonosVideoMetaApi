package su.afk.kemonos.api.video_meta_api.infrastructure.source

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import su.afk.kemonos.api.video_meta_api.config.InspectProperties
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
    properties: InspectProperties,
) {
    private val maxQueuedPerUser = properties.maxQueuedRequestsPerUser.coerceAtLeast(1)
    private val maxQueueWaitMillis = TimeUnit.SECONDS.toMillis(properties.maxQueueWaitSeconds.coerceAtLeast(1))
    private val fileInfoQueue = EndpointQueue(properties.file.maxConcurrentRequests.coerceAtLeast(1))
    private val videoInfoQueue = EndpointQueue(properties.video.maxConcurrentRequests.coerceAtLeast(1))
    private val queuedRequestsByUser = HashMap<String, Int>()

    /**
     * Выполняет блок только после захвата слота лимитера для нужного endpoint.
     */
    fun <T> withPermit(type: InspectRequestType, userKey: String, block: () -> T): T {
        val normalizedUserKey = userKey.trim().ifBlank { "unknown" }.take(64)
        val permit = reservePermit(type, normalizedUserKey)
        val granted = permit.await(maxQueueWaitMillis)
        if (!granted && cancelQueuedPermit(type, permit)) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Inspect request waited too long for an available slot",
            )
        }

        try {
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

    private fun cancelQueuedPermit(type: InspectRequestType, permit: QueuePermit): Boolean =
        synchronized(this) {
            val cancelled = queueFor(type).cancel(permit)
            if (cancelled) {
                decrementQueuedCount(permit.userKey)
            }
            cancelled
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

        fun cancel(permit: QueuePermit): Boolean {
            val userQueue = queuedPermitsByUser[permit.userKey] ?: return false
            val removed = userQueue.remove(permit)
            if (!removed) return false
            if (userQueue.isEmpty()) {
                queuedPermitsByUser.remove(permit.userKey)
                queuedUsers.remove(permit.userKey)
            }
            return true
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

    fun await(maxWaitMillis: Long): Boolean {
        var interrupted = false
        val deadline = System.currentTimeMillis() + maxWaitMillis
        while (true) {
            try {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return restoreInterruptAndReturn(interrupted, false)
                if (latch.await(remaining, TimeUnit.MILLISECONDS)) {
                    return restoreInterruptAndReturn(interrupted, true)
                }
                return restoreInterruptAndReturn(interrupted, false)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }

    private fun restoreInterruptAndReturn(interrupted: Boolean, value: Boolean): Boolean {
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        return value
    }
}
