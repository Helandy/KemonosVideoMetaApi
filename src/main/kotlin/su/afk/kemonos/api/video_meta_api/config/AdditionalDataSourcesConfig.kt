package su.afk.kemonos.api.video_meta_api.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * Поднимает отдельные sqlite datasource для статистики запросов и журнала ошибок источников.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    value = [
        PrimaryDataSourceProperties::class,
        StatisticsDataSourceProperties::class,
        SourceErrorLogDataSourceProperties::class,
    ],
)
class AdditionalDataSourcesConfig {
    @Primary
    @Bean("dataSource")
    fun dataSource(properties: PrimaryDataSourceProperties): DataSource =
        buildDataSource(properties, poolName = "primary-sqlite", maxPoolSize = 4)

    @Primary
    @Bean("jdbcTemplate")
    fun jdbcTemplate(@Qualifier("dataSource") dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource)

    @Bean("statisticsDataSource")
    fun statisticsDataSource(properties: StatisticsDataSourceProperties): DataSource =
        buildDataSource(properties, poolName = "statistics-sqlite", maxPoolSize = 2)

    @Bean("statisticsJdbcTemplate")
    fun statisticsJdbcTemplate(@Qualifier("statisticsDataSource") dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource)

    @Bean("sourceErrorLogDataSource")
    fun sourceErrorLogDataSource(properties: SourceErrorLogDataSourceProperties): DataSource =
        buildDataSource(properties, poolName = "source-error-log-sqlite", maxPoolSize = 2)

    @Bean("sourceErrorLogJdbcTemplate")
    fun sourceErrorLogJdbcTemplate(@Qualifier("sourceErrorLogDataSource") dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource)

    /**
     * Пул намеренно маленький: запись в SQLite всё равно сериализуется,
     * а каждое лишнее соединение — это удерживаемый файловый дескриптор и буферы страниц.
     */
    private fun buildDataSource(
        properties: SqliteDataSourceProperties,
        poolName: String,
        maxPoolSize: Int,
    ): DataSource =
        DataSourceBuilder.create()
            .type(HikariDataSource::class.java)
            .driverClassName(properties.driverClassName)
            .url(properties.url)
            .build()
            .apply {
                this.poolName = poolName
                this.maximumPoolSize = maxPoolSize
                this.minimumIdle = 1
                this.idleTimeout = 60_000
                this.keepaliveTime = 0
            }
}

@ConfigurationProperties("spring.datasource")
class PrimaryDataSourceProperties : SqliteDataSourceProperties()

@ConfigurationProperties("app.statistics.datasource")
class StatisticsDataSourceProperties : SqliteDataSourceProperties()

@ConfigurationProperties("app.source-error-log.datasource")
class SourceErrorLogDataSourceProperties : SqliteDataSourceProperties()

open class SqliteDataSourceProperties {
    var url: String = ""
    var driverClassName: String = "org.sqlite.JDBC"
}
