package su.afk.kemonos.api.video_meta_api.infrastructure.persistence

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import su.afk.kemonos.api.video_meta_api.infrastructure.persistence.converter.SqliteInstantConverter

/**
 * Приводит legacy-значения `created_at` к каноническому формату SQLite.
 *
 * Работает батчами и отбирает только строки, которые действительно нужно переписать,
 * поэтому объём занимаемой памяти не зависит от размера таблицы. Результат фиксируется
 * флагом в `schema_meta`, чтобы миграция не повторялась при каждом старте.
 */
class CreatedAtNormalizer(
    private val jdbcTemplate: JdbcTemplate,
    private val tableName: String,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val instantConverter = SqliteInstantConverter()
    private val migrationKey = "created_at_normalized_v1:$tableName"

    init {
        require(SAFE_TABLE_NAME.matches(tableName)) { "Unsupported table name: $tableName" }
    }

    /**
     * Выполняет нормализацию, если она ещё не была выполнена для этой таблицы.
     */
    fun normalizeOnce() {
        ensureSchemaMetaTable()
        if (isAlreadyNormalized()) return

        var normalizedRows = 0
        while (true) {
            val rows = loadNextBatch()
            if (rows.isEmpty()) break

            var updatedInBatch = 0
            rows.forEach { (id, rawCreatedAt) ->
                val normalized = normalizeOrNull(rawCreatedAt)
                if (normalized != null && normalized != rawCreatedAt) {
                    jdbcTemplate.update(
                        "update $tableName set created_at = ? where id = ?",
                        normalized,
                        id,
                    )
                    updatedInBatch++
                }
            }
            normalizedRows += updatedInBatch

            // Строки, которые не удалось переписать, иначе крутили бы цикл бесконечно.
            if (updatedInBatch == 0) break
        }

        if (normalizedRows > 0) {
            logger.info("Normalized {} legacy created_at values in {}", normalizedRows, tableName)
        }
        markNormalized()
    }

    private fun ensureSchemaMetaTable() {
        jdbcTemplate.execute(
            """
            create table if not exists schema_meta (
                key text primary key,
                value text not null
            )
            """.trimIndent(),
        )
    }

    private fun isAlreadyNormalized(): Boolean {
        val value = jdbcTemplate.query(
            "select value from schema_meta where key = ?",
            { rs, _ -> rs.getString("value") },
            migrationKey,
        ).firstOrNull()
        return value != null
    }

    private fun markNormalized() {
        jdbcTemplate.update(
            "insert or replace into schema_meta (key, value) values (?, ?)",
            migrationKey,
            "done",
        )
    }

    /**
     * Забирает очередную порцию строк, чей `created_at` не соответствует каноническому виду.
     * Фильтрация выполняется в SQLite, поэтому нормальные строки в JVM не попадают.
     */
    private fun loadNextBatch(): List<Pair<Long, String?>> =
        jdbcTemplate.query(
            """
            select id, created_at
            from $tableName
            where created_at is not null
              and created_at not glob '$CANONICAL_TIMESTAMP_GLOB'
            limit ?
            """.trimIndent(),
            { rs, _ -> rs.getLong("id") to rs.getString("created_at") },
            batchSize,
        )

    private fun normalizeOrNull(rawCreatedAt: String?): String? =
        runCatching {
            instantConverter.convertToDatabaseColumn(instantConverter.convertToEntityAttribute(rawCreatedAt))
        }.getOrNull()

    private companion object {
        const val DEFAULT_BATCH_SIZE = 500
        const val CANONICAL_TIMESTAMP_GLOB =
            "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]"
        val SAFE_TABLE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}
