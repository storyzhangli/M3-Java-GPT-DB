# D30 仓库现状扫描报告

扫描时间：2026-11-13（Asia/Shanghai）
扫描时 HEAD：`0d3338e D30 项目基础骨架 1.更改模块名 2.PG配置与维度384`
最终核对 HEAD：`efb0df0 D30 项目问题整改 环境基线是本地 Embedding 384 维`（2026-11-13 23:27:36，任务期间的外部提交，不是本轮创建）
任务书 SHA256：`990FDF3DA73670F0D0D03FD9A6796747D620C641B684A3BFC7B027EC6CEF2007`

## 1. 项目结构

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java
├── D30
│   ├── pom.xml                         # 聚合 POM：org.ai.m2.gpt:D30:1.0-SNAPSHOT，packaging=pom
│   └── D30_postgresqlAndpgvector       # 唯一子模块
│       ├── pom.xml
│       ├── src\main\java\com\zl\d30
│       │   ├── D30SpringAiApplication.java
│       │   ├── config                  # PgDataSourceConfig / PgVectorStoreConfig / Properties / Guard
│       │   ├── document                # 文档导入、切分、Embedding
│       │   └── controller/service/...  # 既有聊天与业务接口
│       ├── src\main\resources
│       │   ├── application.yml
│       │   ├── application-local.yml.example
│       │   └── db                      # MySQL 业务 SQL；本轮新增 db/pgvector 验证 SQL
│       └── src\test\java\com\zl\d30    # 48 个测试源文件
├── docs\proj_docs                      # 技术文档
├── docs\evidence                       # 证据
├── logs                                # 构建/运行日志
└── docs\temp_docs                      # 临时脚本与任务书副本，不自动删除
```

## 2. 实际技术基线

| 项 | 实际值 | 证据 |
|---|---|---|
| JDK | 21.0.7（Oracle，`D:\SoftwareInstall\JDK\JDK21`） | `java -version`、Surefire XML `java.version` |
| Spring Boot | 3.5.16 | `D30/pom.xml` parent |
| Spring AI | 1.1.8，BOM import | `D30/pom.xml` `spring-ai.version` |
| Maven | 3.9.16 | `mvn -version` |
| PostgreSQL 服务端 | 18.7 | `02-postgresql-version.txt` |
| pgvector | 0.8.7 | `04-pgvector-extension.txt` |
| PostgreSQL JDBC | 42.7.11（Boot 依赖管理） | Surefire 测试 classpath |
| MySQL 主库 | 既有业务库，与 PG 数据源隔离 | `application.yml` |
| Redis | 既有依赖与配置；本轮不引入新实例 | `pom.xml` / `application.yml` |

## 3. 已有数据库与向量实现

1. `PgDataSourceConfig` 已存在，使用 `@ConfigurationProperties("spring.datasource.pg")` 绑定独立 `HikariConfig`，Bean 名为 `pgDataSource`。
2. `PgVectorStoreConfig` 已存在，手动装配 `PgVectorStore`，显式排除会误用主 `JdbcTemplate` 的 `PgVectorStoreAutoConfiguration`。
3. `PgVectorStoreProperties` 的 `dimensions=384`、`indexType=HNSW`、`distanceType=COSINE_DISTANCE`、`tableName=vector_store`。
4. `EmbeddingProperties.dimensions=384`，本地 Embedding 模型为 `local-hashing-embedding`。
5. 已有 `PgVectorStoreIntegrationTest` 5 项与 `VectorDimensionGuardTest` 4 项。
6. 既有正式表 `public.vector_store` 已为 `vector(384)`，HNSW 索引使用 `vector_cosine_ops`。

结论：仓库已作出“PostgreSQL + pgvector 作为唯一向量库”的技术决策，本轮沿用，不引入第二个向量库，也不扩展完整 RAG。

## 4. 与任务书要求的差距

| 差距 | 本轮处理 |
|---|---|
| 缺少数据库层专用测试表与 JDBC 验收 | 新增 `PgVectorJdbcAcceptanceTest` 与 `d30-pgvector-verification.sql` |
| PG 口令在 `application.yml` 有明文默认值 | 改为 `${PG_PASSWORD}`，无仓库默认值 |
| `PgDataSourceConfigTest` 使用真实口令并隐式连库 | 改为非真实占位值，并设置 `initialization-fail-timeout=-1` 隔离网络 |
| 仓库内无 Docker Compose | 实际基础设施在虚拟机 `/root/pgvector-stack`；只做只读核验，不复制、不改端口/卷 |
| 证据结构不完整 | 在 `docs/evidence/D30` 下补齐版本、扩展、维度、写入查询、HNSW、JDBC、Maven、Git 证据 |

## 5. Git 与工作区

- Git 根：`E:\AI\project\AI-Learning-three-GPT\M3-Java`
- 分支：`master`
- 扫描时 HEAD：`0d3338e D30 项目基础骨架 1.更改模块名 2.PG配置与维度384`
- 最终核对 HEAD：`efb0df0 D30 项目问题整改 环境基线是本地 Embedding 384 维`（任务期间外部提交，不是本轮创建）
- 工作区存在本轮未提交代码、文档与证据；本轮未执行 reset / clean / checkout 覆盖，也未 commit / push。
- 外部提交 `efb0df0` 已将用户任务书纳入版本库，该文件内含真实 PG 凭据；本轮未复制该凭据到代码或文档。凭据轮换与历史清理需用户授权处理。
- 详细状态见 `11-git-diff-summary.txt`。

## 6. 未改动边界

- 未重建、重启或 down 健康运行的 `pgvector-pg18` 容器。
- 未修改 `docker-compose.yml` 的端口、卷、服务名。
- 未删除历史容器、镜像、数据卷。
- 未操作正式 `vector_store` 表；测试只写 `public.d30_pgvector_test`。
- 未修改 MySQL 业务表结构。
- 未引入 Redis、Nacos、第二向量库、RAG、Agent 工作流。
