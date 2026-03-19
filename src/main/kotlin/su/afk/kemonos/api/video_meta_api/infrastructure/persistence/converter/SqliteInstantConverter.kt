package su.afk.kemonos.api.video_meta_api.infrastructure.persistence.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Хранит `Instant` в SQLite как строку `yyyy-MM-dd HH:mm:ss.SSS` (UTC)
 * и читает как этот формат, так и legacy epoch-секунды/миллисекунды.
 */
@Converter
class SqliteInstantConverter : AttributeConverter<Instant, String> {
    override fun convertToDatabaseColumn(attribute: Instant?): String? {
        if (attribute == null) return null
        return SQLITE_TIMESTAMP_FORMATTER.format(LocalDateTime.ofInstant(attribute, ZoneOffset.UTC))
    }

    override fun convertToEntityAttribute(dbData: String?): Instant? {
        val value = dbData?.trim().orEmpty()
        if (value.isEmpty()) return null

        if (value.all { it.isDigit() }) {
            return if (value.length >= 13) {
                Instant.ofEpochMilli(value.toLong())
            } else {
                Instant.ofEpochSecond(value.toLong())
            }
        }

        return runCatching { Instant.parse(value) }
            .getOrElse {
                LocalDateTime.parse(value, SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC)
            }
    }

    private companion object {
        val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
