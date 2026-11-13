# D30 项目迭代任务书 Prompt：PostgreSQL / pgvector

## 一、项目迭代基本信息

**项目迭代名称：** Day 30：PostgreSQL / pgvector

**项目根目录：**

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\D30
```

**扫描与基础骨架目录：**

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\D30\D30_postgresqlAndpgvector
```

**本次核心任务：**

建立并验证 PostgreSQL + pgvector 向量数据库环境，完成数据库连接、向量扩展、向量维度、向量写入和相似度查询验证，为后续 Spring AI VectorStore、TopK 检索和 RAG 全链路开发提供可靠的数据库基础。

**技术文档目录：**

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\docs\proj_docs
```

**证据目录：**

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\docs\evidence
```

**临时文件目录：**

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\docs\temp_docs
```

**日志目录：**

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\logs
```

---

## 二、角色与执行要求

你是一名具有生产级 Java 后端、Spring AI、PostgreSQL、pgvector 和 Docker 工程实践经验的高级工程师。

本次任务不是单纯编写一份 PostgreSQL 配置文件，而是基于现有 M3-Java 仓库和已经部署的 PostgreSQL + pgvector 环境，完成一次真实、可复现、可验证的工程迭代。

必须遵守以下原则：

1. 先扫描真实仓库，再制定实施方案。
2. 优先复用已有项目结构、技术选型和代码实现。
3. 不得假设仓库中不存在已有向量数据库或 PostgreSQL 配置。
4. 不得为了完成任务而重复搭建已经存在的数据库环境。
5. 所有版本、配置、SQL、目录和代码修改必须有真实依据。
6. 不得编造命令执行结果、测试结果、数据库连接状态或编译结果。
7. 不得为了完成 D30 提前实现完整 RAG 系统。
8. 控制上下文和 Token 消耗，避免无意义的全仓库读取及重复扫描。
9. 每项关键结论必须有代码、配置、SQL 执行结果或其他可核查证据。

---

## 三、已验证环境基线

以下内容是用户提供的已验证环境事实，必须严格遵守。不得擅自替换 IP、版本、目录、端口或连接方式。

### 3.1 虚拟机环境

| 项目            | 配置                              |
| --------------- | --------------------------------- |
| 操作系统        | CentOS Linux 7.9.2009             |
| Kernel          | 3.10.0-1160                       |
| 虚拟化          | VMware NAT                        |
| 虚拟机 IP       | 192.168.200.153                   |
| SSH 端口        | 22                                |
| 账户/密码(凭据) | root/itcast                       |
| CPU             | 4 Core                            |
| 内存            | 3.7 GB                            |
| 可用磁盘        | 17 GB                             |
| 网络            | 仅 Windows 宿主机可达，公网不可达 |

虚拟机是现有 PostgreSQL 数据库部署环境。

### 3.2 Docker 环境

| 项目             | 版本或配置              |
| ---------------- | ----------------------- |
| Docker Engine    | 24.0.9                  |
| Docker API       | 1.43                    |
| Docker Compose   | v2.24.7                 |
| containerd       | 1.6.33                  |
| Storage Driver   | overlay2                |
| Cgroup           | v1                      |
| 镜像加速器       | 阿里云镜像加速器        |
| Compose 项目目录 | `/root/pgvector-stack/` |

必须使用：

```bash
docker compose
```

禁止使用旧版命令：

```bash
docker-compose
```

### 3.3 PostgreSQL + pgvector 环境

当前已经部署 PostgreSQL + pgvector 容器：

| 项目            | 已验证配置                 |
| --------------- | -------------------------- |
| 容器名称        | `pgvector-pg18`            |
| 镜像            | `pgvector/pgvector:pg18`   |
| PostgreSQL 版本 | 18.7                       |
| pgvector 版本   | 0.8.7                      |
| 数据库          | `spring_ai`                |
| 用户名          | `postgres`                 |
| 密码            | Postgres@2026              |
| 主机地址        | `192.168.200.153`          |
| 端口            | `5432`                     |
| 时区            | `Asia/Shanghai`            |
| 数据卷          | `pgvector-stack_pg18_data` |

JDBC URL：

```text
jdbc:postgresql://192.168.200.153:5432/spring_ai
```

pgvector 扩展已经在 `spring_ai` 数据库中启用。

已验证查询：

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';
```

预期已知环境结果：

```text
vector | 0.8.7
```

注意：以上是用户提供的历史验证事实。本次仍须通过实际检查确认当前数据库状态，不能将历史状态直接当成本次执行结果。

### 3.4 Java / Spring AI 技术基线

| 项目                | 版本                                    |
| ------------------- | --------------------------------------- |
| JDK                 | 21                                      |
| Spring Boot         | 3.5.16                                  |
| Spring AI           | 1.1.8                                   |
| Maven               | 3.9.16                                  |
| 应用运行位置        | Windows 本机                            |
| PostgreSQL 运行位置 | CentOS 虚拟机 Docker 容器               |
| 数据库连接方式      | Windows 本机 JDBC 连接虚拟机 PostgreSQL |

Embedding 模型维度：

```text
384
```

当前本地 Embedding 模型使用 384 维向量。

后续如切换模型，可能涉及以下维度：

```text
1536 / 1024 / 768 / 384
```

本次以当前已验证的 384 维为准，不得自行改变。

相似度度量：

```text
cosine
```

pgvector 运算符：

```sql
<=>
```

索引类型：

```text
HNSW
```

索引操作类：

```text
vector_cosine_ops
```

---

## 四、本次任务目标

本次 D30 以 PostgreSQL + pgvector 作为项目唯一向量数据库，完成以下工作。

### 4.1 复核项目已有技术决策

首先检查仓库是否已经存在以下内容：

- PostgreSQL 数据库配置。
- pgvector 依赖或扩展配置。
- Docker Compose 配置。
- Spring AI VectorStore 配置。
- Embedding 模型维度配置。
- 向量表结构及索引。
- 数据库连接工具类或数据访问层。
- 已有数据库初始化 SQL。
- 之前迭代形成的数据库部署文档。

处理原则：

1. 如果仓库不存在现成向量存储，使用 PostgreSQL + pgvector 作为唯一向量数据库。
2. 如果仓库已经存在 PostgreSQL + pgvector 决策，复核其正确性后沿用。
3. 如果仓库使用其他向量数据库，分析现有实现和迁移成本，优先沿用已经合理确定的项目决策，不得擅自引入第二套向量数据库。
4. 如果已有实现不符合当前环境基线，明确指出差异和影响，再进行最小必要修改。
5. 不重复建设已经完成的基础设施。

### 4.2 建立明确的数据库连接配置

确认应用能够从 Windows 本机连接虚拟机 PostgreSQL。

连接信息：

```text
Host: 192.168.200.153
Port: 5432
Database: spring_ai
Username: postgres
Password: Postgres@2026
```

JDBC URL：

```text
jdbc:postgresql://192.168.200.153:5432/spring_ai
```

安全要求：

- 不得将真实密码写入 Git。
- 优先通过环境变量注入。
- 可通过启动参数 `--args` 或环境变量传递配置。
- 提供 `.env.example` 或其他不包含真实凭据的配置示例。
- 检查 `.gitignore`，确保真实环境配置不会被提交。

推荐配置示例：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://192.168.200.153:5432/spring_ai}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:Postgres@2026}
    driver-class-name: org.postgresql.Driver
```

如果项目已有统一配置规范，优先沿用现有规范，不要为了示例而重复创建配置体系。

注意：上述配置仅为参考，必须结合实际模块和 Spring Boot 配置结构实施。

### 4.3 PostgreSQL 与 pgvector 版本复核

通过真实数据库连接确认：

```sql
SELECT version();
```

查询 pgvector 扩展：

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';
```

本次环境基线：

```text
PostgreSQL 18.7
pgvector 0.8.7
```

不得使用：

```text
latest
```

作为镜像版本标签。

如果当前环境版本与基线不一致：

1. 记录实际版本。
2. 分析版本差异。
3. 判断是否影响 Spring AI 和 pgvector 功能。
4. 不得未经用户授权升级、替换或重建现有数据库。

---

## 五、Docker Compose 与虚拟机安全约束

### 5.1 现有 Docker Compose 文件

路径：

```text
/root/pgvector-stack/docker-compose.yml
```

当前容器：

```text
pgvector-pg18
```

必须先检查现有运行状态，再决定是否需要执行运维命令。

只读检查命令：

```bash
cd /root/pgvector-stack
docker compose ps
```

检查容器：

```bash
docker inspect pgvector-pg18
```

检查日志：

```bash
cd /root/pgvector-stack
docker compose logs --tail=100
```

### 5.2 Docker Compose 启动与健康检查

本次需要确认：

- PostgreSQL 容器是否存在。
- 容器是否正常运行。
- 数据库是否完成初始化。
- PostgreSQL 是否通过健康检查。
- Windows 宿主机是否能够访问数据库端口。
- pgvector 扩展是否能够正常使用。

如果容器已经正常运行，不要为了重复验证而停止或重建容器。

如果容器停止：

1. 先分析停止原因。
2. 检查容器状态和日志。
3. 确认启动操作不会修改现有数据。
4. 确有必要时才执行启动命令。

启动命令：

```bash
cd /root/pgvector-stack
docker compose up -d
```

不得将以下命令作为常规验证步骤：

```bash
docker compose down
```

### 5.3 Docker 环境保护红线

严禁：

1. 删除虚拟机上现有的 14 个历史停止容器。
2. 删除虚拟机上现有的 18 个旧镜像。
3. 修改 `/root/pgvector-stack/docker-compose.yml` 的端口映射。
4. 修改现有数据卷挂载。
5. 删除或重建 `pgvector-stack_pg18_data` 数据卷。
6. 执行 `docker system prune`。
7. 执行 `docker volume prune`。
8. 执行 `docker image prune -a`。
9. 未经授权重建 PostgreSQL 容器。
10. 未经授权执行数据库初始化重置、删除数据库或清空数据。

如发现现有 Compose 配置需要调整，先说明原因、影响和修改方案，等待用户明确授权。

---

## 六、pgvector 扩展与向量维度验证

### 6.1 扩展启用验证

连接数据库：

```sql
SELECT current_database();
```

确认当前数据库为：

```text
spring_ai
```

查询扩展：

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';
```

如果扩展不存在，可以在确认连接的是正确数据库后执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

再次查询扩展版本。

注意：

- 不要在错误数据库中启用扩展。
- 不要假设其他数据库的扩展状态与 `spring_ai` 相同。
- 扩展启用失败时必须记录真实错误。

### 6.2 向量维度确认

当前 Embedding 模型维度：

```text
384
```

必须确认项目实际使用的 Embedding 模型输出维度为 384。

验证依据可以包括：

- D29 已有模型配置。
- 已有 Embedding 实际调用结果。
- 模型服务返回的向量长度。
- 项目已有测试记录。

不得仅根据模型名称推测向量维度。

本次向量字段必须使用：

```sql
vector(384)
```

如果实际模型输出维度与 384 不一致：

1. 停止向量表创建或写入。
2. 查明模型配置与维度差异。
3. 记录差异及其影响。
4. 不得擅自修改模型或数据库结构。
5. 等待用户确认正确维度。

### 6.3 向量表设计

创建独立的 D30 验证表，避免污染已有业务表。

表名建议：

```text
d30_pgvector_test
```

表结构示例：

```sql
CREATE TABLE IF NOT EXISTS d30_pgvector_test (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    embedding VECTOR(384) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

说明：

- 384 必须与当前 Embedding 模型维度一致。
- 如果仓库已有统一命名规范，沿用项目规范。
- 如果表已存在，先检查表结构，禁止直接覆盖。
- 不得为了测试删除已有数据。
- 不得将测试表与后续正式业务表混淆。

### 6.4 插入测试向量

使用符合 384 维要求的测试向量进行写入。

测试向量可以使用可控的合成数据，但必须明确标注为测试数据，不得冒充真实 Embedding 模型输出。

插入 SQL 示例：

```sql
INSERT INTO d30_pgvector_test (content, embedding)
VALUES (
    'D30 pgvector test',
    ('[' || array_to_string(
        ARRAY(SELECT 0.1::real FROM generate_series(1, 384)),
        ','
    ) || ']')::vector
);
```

如果 PostgreSQL 对上述表达式或类型转换存在兼容性问题，应根据实际报错调整 SQL，并保留最终成功执行的 SQL。

必须检查：

```sql
SELECT id, content, vector_dims(embedding)
FROM d30_pgvector_test;
```

预期维度：

```text
384
```

### 6.5 相似度查询

必须验证 pgvector 的 cosine 距离运算符：

```sql
<=>
```

示例：

```sql
SELECT
    id,
    content,
    embedding <=> (
        '[' || array_to_string(
            ARRAY(SELECT 0.1::real FROM generate_series(1, 384)),
            ','
        ) || ']'
    )::vector AS cosine_distance
FROM d30_pgvector_test
ORDER BY embedding <=> (
    '[' || array_to_string(
        ARRAY(SELECT 0.1::real FROM generate_series(1, 384)),
        ','
    ) || ']'
)::vector
LIMIT 5;
```

必须确认：

- SQL 能够成功执行。
- 查询结果能够返回。
- 距离值符合 pgvector 的计算语义。
- 查询排序符合距离升序逻辑。

注意：

`<=>` 返回的是 cosine distance，不是 cosine similarity。

如需要相似度值，可以使用：

```sql
1 - (embedding <=> query_vector)
```

不得混淆距离和相似度。

### 6.6 错误维度验证

在独立测试操作中验证维度不匹配时的行为。

例如，使用少于 384 维的向量尝试写入 `vector(384)` 字段。

预期：

- 数据库拒绝维度不匹配的数据。
- 不产生错误数据。
- 能够记录实际异常信息。

不得为了验证错误维度而修改正式业务表。

---

## 七、HNSW 索引验证

本次需要确认 PostgreSQL + pgvector 支持后续 cosine 相似度检索所需的 HNSW 索引。

索引示例：

```sql
CREATE INDEX IF NOT EXISTS idx_d30_pgvector_test_embedding
ON d30_pgvector_test
USING hnsw (embedding vector_cosine_ops);
```

验证索引：

```sql
SELECT
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'd30_pgvector_test';
```

必须明确：

1. HNSW 是近似最近邻索引。
2. `vector_cosine_ops` 与 cosine 距离运算符相匹配。
3. 小规模测试数据不一定会触发索引扫描。
4. 索引创建成功不等于查询执行计划一定使用索引。
5. 是否使用索引必须结合 `EXPLAIN` 的真实结果判断。

可使用：

```sql
EXPLAIN
SELECT
    id,
    content,
    embedding <=> (
        '[' || array_to_string(
            ARRAY(SELECT 0.1::real FROM generate_series(1, 384)),
            ','
        )
    )::vector AS distance
FROM d30_pgvector_test
ORDER BY embedding <=> (
    '[' || array_to_string(
        ARRAY(SELECT 0.1::real FROM generate_series(1, 384)),
        ','
    )
)::vector
LIMIT 5;
```

如果数据规模太小导致优化器选择顺序扫描，必须如实说明，不得为了证明索引生效而伪造执行计划。

---

## 八、Spring Boot / Spring AI 接入边界

本次重点是 PostgreSQL + pgvector 数据库基础能力验证。

必须先检查现有项目结构和依赖，再决定是否需要增加 Java 代码。

### 8.1 依赖检查

检查：

- PostgreSQL JDBC Driver。
- Spring Boot JDBC / DataSource 配置。
- Spring AI 1.1.8 相关依赖。
- 项目是否已经引入 Spring AI VectorStore 相关模块。

遵循最小必要修改原则。

不得重复添加已有依赖。

不得升级 JDK、Spring Boot、Spring AI 或 Maven 版本。

### 8.2 本次 Java 验证范围

如果现有项目结构适合，可以实现最小化 JDBC 验证：

1. 应用能够连接 PostgreSQL。
2. 能够查询 PostgreSQL 版本。
3. 能够查询 pgvector 扩展版本。
4. 能够确认向量字段维度。
5. 能够完成测试向量插入。
6. 能够执行 `<=>` 相似度查询。

所有验证必须使用真实 PostgreSQL 数据库。

如果数据库不可用：

- 必须明确标记数据库验证失败。
- 不得通过 Mock 结果冒充真实数据库结果。
- 不得宣称向量写入成功。
- 不得宣称相似度检索成功。

### 8.3 本次不实施的内容

除非现有项目已经具备且确实需要维护，否则本次不扩展以下功能：

- 完整 Spring AI VectorStore 业务实现。
- RAG 全链路。
- TopK 检索业务。
- 混合检索。
- Rerank。
- MCP。
- Agent Workflow。
- 多租户向量数据库架构。
- 生产级向量数据迁移系统。

这些内容应留给后续独立迭代。

---

## 九、仓库扫描与实施步骤

严格按照以下顺序执行。

### 阶段一：扫描仓库

扫描项目根目录：

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\D30
```

扫描基础骨架：

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\D30\D30_postgresqlAndpgvector
```

重点检查：

- `pom.xml`
- 子模块 `pom.xml`
- `application.yml`
- `application-*.yml`
- `Dockerfile`
- `docker-compose.yml`
- 数据库初始化 SQL
- Spring AI 配置
- D29 Embedding 实现与配置
- 已有向量数据库实现
- `.gitignore`
- README
- 既有测试代码
- 既有技术文档

扫描要求：

1. 优先通过文件树、脚本和定向搜索获取结构信息。
2. 不要无目的地读取整个仓库。
3. 不要重复读取已经确认的文件。
4. 先识别已有实现，再决定修改范围。
5. 记录当前 Git 分支、HEAD 和工作区状态。
6. 用户已有未提交修改时必须保护，不得覆盖。

### 阶段二：形成实施方案

输出简洁的扫描结论：

| 检查项目            | 当前状态 | 处理方案         |
| ------------------- | -------- | ---------------- |
| PostgreSQL 连接配置 | 待扫描   | 复用或补充       |
| pgvector 扩展       | 待验证   | 确认版本         |
| Embedding 维度      | 待核对   | 以实际模型为准   |
| 向量表              | 待扫描   | 复用或建立测试表 |
| HNSW 索引           | 待扫描   | 按需创建         |
| JDBC 验证           | 待扫描   | 补充最小实现     |
| Spring AI 依赖      | 待扫描   | 不重复添加       |
| 测试                | 待扫描   | 补充必要验证     |

必须先确定修改范围，再开始编码。

### 阶段三：实施

根据扫描结果执行：

1. PostgreSQL 配置复核。
2. JDBC 连接配置。
3. pgvector 扩展验证。
4. 向量维度验证。
5. 测试向量表结构验证。
6. 向量插入和读取。
7. cosine 距离查询。
8. HNSW 索引验证。
9. 必要的 Java JDBC 验证。
10. 补充自动化测试。

所有修改遵循最小化原则。

### 阶段四：真实验证

必须实际执行适用的验证命令，并记录真实结果。

Maven 编译：

```bash
mvn -pl <实际模块名> -am clean compile
```

如果项目为单模块，使用：

```bash
mvn clean compile
```

测试：

```bash
mvn -pl <实际模块名> -am test
```

如果项目为单模块，使用：

```bash
mvn test
```

注意：

- 根据实际模块结构选择命令。
- 不得照抄占位模块名。
- 编译失败必须记录失败原因。
- 测试未执行不得宣称测试通过。
- 数据库连接失败不得宣称数据库验证通过。
- 不得用 Mock 替代本次必须完成的真实数据库验证。

### 阶段五：整理证据

证据统一存放：

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\docs\evidence\D30
```

建议包含：

```text
D30/
├── 01-repository-scan.md
├── 02-postgresql-version.txt
├── 03-pgvector-extension.txt
├── 04-vector-dimension.txt
├── 05-vector-insert-query.txt
├── 06-hnsw-index.txt
├── 07-jdbc-connection.txt
├── 08-maven-compile.txt
├── 09-maven-test.txt
└── 10-git-diff-summary.txt
```

以上是建议结构，应根据真实执行情况调整。

不得创建没有实际内容的虚假证据文件。

不得在证据、日志和提交内容中暴露数据库密码、API Key 等敏感信息。

---

## 十、技术文档交付

文档输出目录：

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\docs\proj_docs
```

本次必须交付两份 Markdown 文档。

### 文档一：项目迭代全量说明

文件名：

```text
D30-PostgreSQL与pgvector本地环境部署及项目迭代全量说明.md
```

内容必须包含：

1. D30 迭代背景。
2. 本次目标与范围。
3. 仓库扫描结果。
4. 项目原有数据库技术决策。
5. PostgreSQL 与 pgvector 环境信息。
6. Docker Compose 环境检查与启动操作。
7. 数据库连接配置。
8. pgvector 扩展启用与版本查询。
9. Embedding 维度确认。
10. 向量表结构。
11. 测试向量写入和读取。
12. cosine 距离查询。
13. HNSW 索引创建与验证。
14. Java / Spring AI 接入情况。
15. 实际修改文件清单。
16. 实际执行命令及结果。
17. Maven 编译与测试结果。
18. 已知问题与限制。
19. Git 变更和提交信息。
20. D31 后续衔接说明。

必须体现本次实际完成的内容，不得把计划内容写成已完成内容。

### 文档二：技术原理与操作实践指南

文件名：

```text
D30-PostgreSQL与pgvector技术原理及本地部署操作实践指南.md
```

内容必须包含：

1. PostgreSQL 的定位。
2. pgvector 的作用和基本原理。
3. PostgreSQL 与 pgvector 的关系。
4. 为什么 Java AI 项目可以使用 PostgreSQL + pgvector。
5. 向量数据的基本概念。
6. Embedding 向量维度的含义。
7. `vector(N)` 字段的作用。
8. 向量写入与读取。
9. cosine distance 的计算语义。
10. `<=>` 运算符。
11. cosine similarity 与 cosine distance 的区别。
12. HNSW 索引的基本原理。
13. `vector_cosine_ops` 的作用。
14. Docker Compose 部署架构。
15. 数据持久化与数据卷。
16. Spring Boot JDBC 连接实践。
17. 常见连接问题排查。
18. pgvector 常见 SQL。
19. 维度不匹配问题。
20. 后续 Spring AI VectorStore 的衔接。

文档必须适合 Typora 阅读，使用标准 Markdown。

技术内容应结合实际 SQL、Java 配置、命令和运行结果解释，不要只罗列概念。

### 技术参考资料要求

引用官方技术文档或可信技术资料。

所有外部参考链接使用以下格式：

```text
English Original Title | 准确中文翻译标题：[完整 URL](完整 URL)
```

要求：

- 优先使用 PostgreSQL 官方文档。
- 优先使用 pgvector 官方 GitHub 文档。
- 优先使用 Spring AI 1.1.8 对应文档。
- 不得引用与当前版本明显不匹配的旧版 API。
- 不得编造 URL。
- 不得使用只有网站名称、没有完整链接的参考资料。

---

## 十一、临时文件与日志管理

### 11.1 临时文件目录

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\docs\temp_docs
```

本目录用于存放：

- 临时扫描脚本。
- SQL 测试脚本。
- 临时配置文件。
- 临时文本。
- 中间分析结果。
- 辅助验证文件。

**特别注意：**

1. 不得自动删除该目录中的任何文件。
2. 不得清空该目录。
3. 不得覆盖用户已有文件。
4. 不得擅自整理、移动或重命名用户原有文件。
5. 新增临时文件时使用 D30 专属文件名。
6. 是否删除由用户自行决定。

### 11.2 日志目录

```text
E:\AI\project\AI-Learning-three-GPT\M3-Java\logs
```

建议：

```text
logs/
└── D30/
    ├── repository-scan.log
    ├── maven-compile.log
    ├── maven-test.log
    └── database-validation.log
```

日志必须脱敏。

禁止记录：

- 数据库真实密码。
- Embedding API Key。
- 完整敏感环境变量。
- SSH 登录密码。

---

## 十二、验收标准

必须按实际结果逐项验收。

| 编号   | 验收项目        | 验收标准                             |
| ------ | --------------- | ------------------------------------ |
| D30-01 | 仓库扫描        | 已识别真实模块、POM 和既有数据库实现 |
| D30-02 | 技术决策        | PostgreSQL + pgvector 方案已经复核   |
| D30-03 | Docker 状态     | 已确认容器状态，未破坏现有环境       |
| D30-04 | PostgreSQL 版本 | 实际查询确认版本                     |
| D30-05 | pgvector 扩展   | 已确认扩展存在及版本                 |
| D30-06 | 数据库连接      | Windows 本机 JDBC 真实连接成功       |
| D30-07 | 向量维度        | 已确认实际 Embedding 输出维度为 384  |
| D30-08 | 向量字段        | 已验证 `vector(384)`                 |
| D30-09 | 向量写入        | 测试向量真实写入成功                 |
| D30-10 | 向量读取        | 能够查询已写入的向量记录             |
| D30-11 | 相似度查询      | `<=>` 查询成功并返回距离结果         |
| D30-12 | HNSW 索引       | 索引创建及定义验证成功               |
| D30-13 | 错误维度        | 不匹配维度被数据库拒绝               |
| D30-14 | Maven 编译      | 实际编译成功                         |
| D30-15 | Maven 测试      | 实际执行并记录测试结果               |
| D30-16 | 技术文档        | 两份 Markdown 文档完整交付           |
| D30-17 | 证据归档        | 关键验证证据已保存                   |
| D30-18 | 环境安全        | 未破坏既有容器、镜像、数据卷和配置   |

每项验收状态只能使用：

- PASS：有真实证据证明通过。
- FAIL：实际执行失败。
- BLOCKED：因环境或前置条件无法执行。
- NOT RUN：尚未执行。
- N/A：经说明确认不适用。

不得将 BLOCKED、NOT RUN 或 N/A 统计为 PASS。

### 特别验收规则

以下情况必须严格区分：

| 实际情况                | 允许的结论            |
| ----------------------- | --------------------- |
| Docker 容器运行         | 容器运行正常          |
| PostgreSQL 健康检查通过 | 数据库健康检查通过    |
| JDBC 连接成功           | 应用可以连接数据库    |
| pgvector 扩展查询成功   | 向量扩展可用          |
| 向量写入成功            | 向量写入验证通过      |
| `<=>` 查询成功          | 相似度查询验证通过    |
| HNSW 索引创建成功       | HNSW 索引创建验证通过 |
| Maven 编译成功          | Java 工程编译通过     |

**禁止将容器运行成功直接描述为向量检索成功。**

如果数据库启动失败，数据库连接、向量写入、相似度查询等相关项目必须标记为 BLOCKED 或 FAIL，不能宣称检索通过。

---

## 十三、Git 与变更管理

实施前：

1. 查看当前分支。
2. 查看 HEAD。
3. 查看工作区状态。
4. 检查用户未提交的修改。
5. 不覆盖、不清理用户已有修改。

实施后：

1. 查看 `git status`。
2. 查看 `git diff --stat`。
3. 检查关键文件差异。
4. 检查敏感信息。
5. 确认技术文档和证据文件。
6. 按项目现有提交规范整理变更。

如果仓库已经存在提交规范，优先遵循。

未经用户明确要求，不要擅自执行破坏性 Git 操作，例如：

```bash
git reset --hard
git clean -fd
```

不得将数据库密码、API Key 或其他敏感配置提交到 Git。

---

## 十四、Token 与上下文成本控制

本次任务应采用小步迭代，避免无意义的 Token 消耗。

具体要求：

1. 先读取项目目录结构和 POM。
2. 只读取与本次任务相关的配置和代码。
3. 不要反复扫描整个仓库。
4. 不要重复读取已经确认的文件。
5. 不要大段输出无关依赖树。
6. 不要将完整 Maven 日志直接复制到对话。
7. 使用命令行脚本提取关键结果。
8. 仅保留关键错误、警告和验证摘要。
9. 修改代码前先确定最小变更范围。
10. 一次完成一组相互关联的修改，避免反复重构。
11. 不要为了追求所谓生产级而提前引入复杂架构。
12. 技术文档应以真实变更和验证结果为依据，避免重复生成冗长内容。

最终需要交付可追溯证据，而不是大量过程性文本。

---

## 十五、最终交付清单

完成任务后，必须给出清晰的交付摘要。

### 15.1 代码交付

- [ ] PostgreSQL 连接配置。
- [ ] pgvector 扩展验证。
- [ ] 向量维度验证。
- [ ] 测试向量表及 SQL。
- [ ] 向量写入和读取验证。
- [ ] cosine 相似度查询验证。
- [ ] HNSW 索引验证。
- [ ] 必要的 Java 验证代码。
- [ ] 敏感配置保护。

### 15.2 文档交付

- [ ] `D30-PostgreSQL与pgvector本地环境部署及项目迭代全量说明.md`
- [ ] `D30-PostgreSQL与pgvector技术原理及本地部署操作实践指南.md`

### 15.3 证据交付

- [ ] 仓库扫描证据。
- [ ] PostgreSQL 版本证据。
- [ ] pgvector 扩展版本证据。
- [ ] 向量维度证据。
- [ ] 向量写入与查询证据。
- [ ] HNSW 索引证据。
- [ ] JDBC 连接证据。
- [ ] Maven 编译证据。
- [ ] Maven 测试证据。
- [ ] Git 变更摘要。

### 15.4 最终汇报格式

最终汇报必须采用以下结构：

**一、本次迭代总结**

简要说明 D30 实际完成了什么。

**二、仓库扫描结果**

说明实际项目结构、已有数据库实现和本次修改范围。

**三、技术实现**

说明 PostgreSQL、pgvector、JDBC、向量维度、相似度查询和 HNSW 索引的实际实现情况。

**四、真实验证结果**

使用表格：

| 验证项目        | 状态 | 真实结果 | 证据位置 |
| --------------- | ---- | -------- | -------- |
| PostgreSQL 连接 |      |          |          |
| pgvector 扩展   |      |          |          |
| 向量维度        |      |          |          |
| 向量写入        |      |          |          |
| 相似度查询      |      |          |          |
| HNSW 索引       |      |          |          |
| Maven 编译      |      |          |          |
| Maven 测试      |      |          |          |

**五、文件变更清单**

列出实际新增、修改的文件。

**六、技术文档位置**

给出两份 Markdown 文档的完整路径。

**七、Git 信息**

说明分支、提交号和工作区状态。

**八、未完成事项与风险**

如实说明 BLOCKED、FAIL、NOT RUN 项目及原因。

**九、D31 衔接建议**

结合 D30 实际完成情况，给出下一阶段 Spring AI VectorStore 的最小必要衔接方案。

---

## 十六、D30 最终完成定义

D30 的核心目标是：

**让 PostgreSQL + pgvector 成为 M3-Java 项目中经过真实验证、版本明确、可重复连接和操作的向量数据库基础设施。**

本次迭代完成后，应当能够证明：

1. PostgreSQL 18.7 数据库能够正常运行。
2. pgvector 0.8.7 扩展能够正常使用。
3. Windows 本机 Java 应用能够通过 JDBC 连接虚拟机数据库。
4. 当前 Embedding 模型输出维度为 384。
5. `vector(384)` 能够正确保存向量。
6. 能够真实执行向量写入和读取。
7. 能够使用 `<=>` 完成 cosine 距离查询。
8. 能够创建并验证 HNSW 索引。
9. Maven 编译和相关测试有真实执行结果。
10. 所有关键结论均有证据和技术文档支撑。

**本次不以代码行数、文件数量或架构复杂度作为完成标准，以真实运行、正确验证、工程可复现和证据完整作为验收标准。**

开始执行时，先进行仓库扫描和环境只读检查，形成实施方案，再进行必要的代码和配置修改。不得跳过检查直接重建数据库环境。