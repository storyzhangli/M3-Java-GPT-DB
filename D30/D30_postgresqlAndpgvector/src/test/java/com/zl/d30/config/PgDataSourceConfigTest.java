package com.zl.d30.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双数据源装配测试：验证 MySQL 主库仍为 @Primary，PG 池参数正确绑定。
 *
 * <p>该测试只用 {@link ApplicationContextRunner} 装载两个配置类，不引入自动配置、不建立真实连接
 * （Hikari 懒初始化），因此无需 PG / MySQL 在线即可运行。</p>
 */
class PgDataSourceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PrimaryDataSourceConfig.class, PgDataSourceConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/ai_db",
                    "spring.datasource.username=root",
                    "spring.datasource.password=123456",
                    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                    "spring.datasource.pg.jdbc-url=jdbc:postgresql://192.168.200.153:5432/spring_ai",
                    "spring.datasource.pg.username=postgres",
                    "spring.datasource.pg.password=Postgres@2026",
                    "spring.datasource.pg.driver-class-name=org.postgresql.Driver",
                    "spring.datasource.pg.pool-name=PgVectorHikariPool",
                    "spring.datasource.pg.maximum-pool-size=10",
                    "spring.datasource.pg.minimum-idle=2");

    @Test
    @DisplayName("MySQL 主数据源保持 @Primary，且连接参数指向 MySQL")
    void primaryDataSourceIsMysql() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            DataSource primary = context.getBean(DataSource.class);
            assertThat(primary).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikari = (HikariDataSource) primary;
            assertThat(hikari.getJdbcUrl()).startsWith("jdbc:mysql://127.0.0.1:3306/ai_db");
            assertThat(context.getBeanFactory().getBeanDefinition("dataSource").isPrimary()).isTrue();
        });
    }

    @Test
    @DisplayName("PG 数据源与主库并存，池名与容量按配置绑定")
    void pgDataSourceBoundFromProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.containsBean("pgDataSource")).isTrue();
            HikariDataSource pg = context.getBean("pgDataSource", HikariDataSource.class);
            assertThat(pg.getJdbcUrl()).isEqualTo("jdbc:postgresql://192.168.200.153:5432/spring_ai");
            assertThat(pg.getUsername()).isEqualTo("postgres");
            assertThat(pg.getPoolName()).isEqualTo("PgVectorHikariPool");
            assertThat(pg.getMaximumPoolSize()).isEqualTo(10);
            assertThat(pg.getMinimumIdle()).isEqualTo(2);
            // 两个数据源互不覆盖：PG 池不是主 Bean
            assertThat(context.getBeanFactory().getBeanDefinition("pgDataSource").isPrimary()).isFalse();
        });
    }

    @Test
    @DisplayName("pg 节点出现未知键（如嵌套 hikari:）时启动即失败，避免池参数被静默忽略")
    void unknownPgPropertyFailsFast() {
        runner.withPropertyValues("spring.datasource.pg.hikari.pool-name=NestedPool")
                .run(context -> assertThat(context).hasFailed());
    }
}
