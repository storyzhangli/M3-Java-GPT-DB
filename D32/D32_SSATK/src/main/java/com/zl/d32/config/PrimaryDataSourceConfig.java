package com.zl.d32.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * MySQL 主数据源（显式声明，保持 {@code spring.datasource.*} 连接参数零改动）。
 *
 * <p>为什么必须显式声明：Spring Boot 的 {@code DataSourceAutoConfiguration} 以
 * {@code @ConditionalOnMissingBean({DataSource.class, XADataSource.class})} 为前置条件。
 * 为了让向量库落在 PostgreSQL，新注册了 {@code pgDataSource}，这会令自动配置整体退让，
 * 若不同时显式声明主库，容器里就只剩 PG 数据源，MyBatis-Plus 会把 Prompt 模板 / 聊天历史
 * 全部写到 PostgreSQL，造成“MySQL 静默失效”的隐性回归。</p>
 *
 * <p>因此这里按 Spring Boot 官方多数据源做法显式声明主库，并标记 {@link Primary}：
 * MyBatis-Plus 的 {@code @ConditionalOnSingleCandidate(DataSource.class)} 与任何按类型注入
 * 都会稳定命中本 Bean；连接参数直接复用 {@code spring.datasource.url/username/password/driver-class-name}，
 * 未新增、未修改任何 MySQL 连接项。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties.class)
public class PrimaryDataSourceConfig {

    /**
     * MySQL 主数据源：与 Spring Boot 自动配置同构（DataSourceProperties 初始化 → HikariDataSource）。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
