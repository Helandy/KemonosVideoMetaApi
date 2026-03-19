package su.afk.kemonos.api.video_meta_api.config

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
        buildDataSource(properties)

    @Primary
    @Bean("jdbcTemplate")
    fun jdbcTemplate(@Qualifier("dataSource") dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource)

    @Bean("statisticsDataSource")
    fun statisticsDataSource(properties: StatisticsDataSourceProperties): DataSource =
        buildDataSource(properties)

    @Bean("statisticsJdbcTemplate")
    fun statisticsJdbcTemplate(@Qualifier("statisticsDataSource") dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource)

    @Bean("sourceErrorLogDataSource")
    fun sourceErrorLogDataSource(properties: SourceErrorLogDataSourceProperties): DataSource =
        buildDataSource(properties)

    @Bean("sourceErrorLogJdbcTemplate")
    fun sourceErrorLogJdbcTemplate(@Qualifier("sourceErrorLogDataSource") dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource)

    private fun buildDataSource(properties: SqliteDataSourceProperties): DataSource =
        DataSourceBuilder.create()
            .driverClassName(properties.driverClassName)
            .url(properties.url)
            .build()
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
