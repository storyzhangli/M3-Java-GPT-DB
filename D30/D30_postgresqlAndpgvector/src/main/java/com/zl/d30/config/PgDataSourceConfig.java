package com.zl.d30.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * pgvector 专用 PostgreSQL 数据源（与 MySQL 主数据源完全隔离）。
 *
 * <p>为什么必须独立：{@code spring.datasource.*} 是 MySQL 主库（Prompt 模板 / 聊天历史的权威存储），
 * 向量库必须落在 PostgreSQL；两者连接参数、驱动、方言都不同，混用会直接写坏数据。</p>
 *
 * <p>为什么不用 Spring AI 自动配置：经 1.1.8 jar 反编译核验，
 * {@code PgVectorStoreAutoConfiguration#vectorStore} 依赖容器内的
 * {@code JdbcTemplate}（即主数据源构建的那个），排除它并在此手动装配才能真正绑定 pgDataSource。</p>
 *
 * <p>绑定点：{@code @ConfigurationProperties("spring.datasource.pg")} 直接绑定 {@link HikariConfig}，
 * 因此 YAML 中 {@code jdbc-url / username / password / driver-class-name / pool-name /
 * maximum-pool-size / minimum-idle / connection-timeout / idle-timeout / max-lifetime}
 * 必须与 HikariConfig 的 setter 同层书写；写成 {@code pg.hikari.pool-name} 这类嵌套节点不会被绑定
 * （{@link HikariConfig} 没有 {@code hikari} 属性），这里用 {@code ignoreUnknownFields=false} 让这类错误启动即失败。</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class PgDataSourceConfig {

    /**
     * PG 连接池配置载体：仅做属性绑定，不建立连接（Hikari 是懒初始化）。
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.pg", ignoreUnknownFields = false)
    public HikariConfig pgHikariConfig() {
        return new HikariConfig();
    }

    /**
     * PG 数据源：按 {@link HikariConfig} 构建连接池，容器关闭时自动 close。
     */
    @Bean(name = "pgDataSource", destroyMethod = "close")
    public DataSource pgDataSource(@Qualifier("pgHikariConfig") HikariConfig pgHikariConfig) {
        HikariDataSource dataSource = new HikariDataSource(pgHikariConfig);
        log.info("PG 向量库数据源装配完成：poolName={}，jdbcUrl={}，maximumPoolSize={}",
                pgHikariConfig.getPoolName(),
                maskPassword(pgHikariConfig.getJdbcUrl()),
                pgHikariConfig.getMaximumPoolSize());
        return dataSource;
    }

    /**
     * 日志脱敏：JDBC URL 中若出现 user:password@host 形式，只保留结构，抹掉口令。
     */
    private String maskPassword(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "null";
        }
        return jdbcUrl.replaceAll("(?<=://)[^/@:]+:[^/@]+@", "***:***@");
    }
}
