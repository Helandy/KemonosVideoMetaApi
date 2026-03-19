package su.afk.kemonos.api.video_meta_api.application.security

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Управляет админ-ключом и защитой от перебора по IP.
 */
@Service
class AdminKeyService(
    @Value("\${app.admin.key.path:/data/.admin.key}")
    private val adminKeyPath: String,
    @Value("\${app.admin.max-failed-attempts:10}")
    private val maxFailedAttempts: Int,
    @Value("\${app.admin.ban-minutes:60}")
    private val banMinutes: Long,
) {
    @Volatile
    private lateinit var adminKey: String
    private val attemptsByIp = ConcurrentHashMap<String, FailedAttemptState>()

    /**
     * Загружает ключ из файла или генерирует новый при первом запуске.
     */
    @PostConstruct
    fun initAdminKey() {
        val file = Path.of(adminKeyPath)
        Files.createDirectories(file.parent ?: Path.of("."))
        if (Files.exists(file)) {
            val existing = Files.readString(file).trim()
            if (existing.length == 64) {
                adminKey = existing
                return
            }
        }

        val generated = generateHexKey(32)
        Files.writeString(file, "$generated\n")
        adminKey = generated
    }

    /**
     * Проверяет ключ администратора и применяет бан по IP после серии неудачных попыток.
     */
    fun validateOrThrow(rawKey: String?, clientIp: String) {
        val ip = clientIp.ifBlank { "unknown" }
        val now = Instant.now()
        val state = attemptsByIp.computeIfAbsent(ip) { FailedAttemptState() }

        synchronized(state) {
            val bannedUntil = state.bannedUntil
            if (bannedUntil != null && bannedUntil.isAfter(now)) {
                throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "IP temporarily banned until $bannedUntil")
            }

            if (isValidKey(rawKey)) {
                state.failedAttempts = 0
                state.bannedUntil = null
                return
            }

            state.failedAttempts += 1
            if (state.failedAttempts >= maxFailedAttempts.coerceAtLeast(1)) {
                state.failedAttempts = 0
                state.bannedUntil = now.plus(Duration.ofMinutes(banMinutes.coerceAtLeast(1)))
                throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many invalid admin key attempts")
            }

            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid admin key")
        }
    }

    /**
     * Проверяет ключ администратора без изменений состояния, чтобы его можно было
     * использовать в инфраструктурных интеграциях.
     */
    fun isValid(rawKey: String?): Boolean = isValidKey(rawKey)

    /**
     * Возвращает текущее значение admin key для интеграции с Spring Security.
     */
    fun currentKey(): String = adminKey

    /**
     * Выполняет безопасную проверку совпадения ключей.
     */
    private fun isValidKey(rawKey: String?): Boolean {
        val provided = rawKey?.trim().orEmpty()
        if (provided.length != 64) return false
        val expectedBytes = adminKey.toByteArray(Charsets.UTF_8)
        val providedBytes = provided.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, providedBytes)
    }

    /**
     * Генерирует случайный hex-ключ заданной длины.
     */
    private fun generateHexKey(sizeBytes: Int): String {
        val bytes = ByteArray(sizeBytes)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Состояние неудачных попыток авторизации для одного IP.
 */
private class FailedAttemptState(
    var failedAttempts: Int = 0,
    var bannedUntil: Instant? = null,
)
