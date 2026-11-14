-- ============================================================================
-- D31 向量索引台账 DDL（MySQL 8.x，InnoDB，utf8mb4）
-- 库：ai_db
--
-- 设计边界：
--   1) MySQL 只保存任务状态、Chunk 内容快照与稳定 UUID，不保存向量本体；
--   2) 向量本体写入 PostgreSQL/pgvector，由 Spring AI 1.1.8 管理表结构；
--   3) 两库不共享事务，跨系统一致性由状态机 + 稳定 ID + 重试/补偿保证；
--   4) 表名/列名与 D31 真机结构一致，可重复执行，不删除已有数据。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `vector_index_task` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`      VARCHAR(64)  NOT NULL COMMENT '文档业务标识',
    `version`          VARCHAR(64)  NOT NULL COMMENT '文档版本，业务主键的一部分',
    `strategy`         VARCHAR(32)  NOT NULL COMMENT '切分策略 FIXED_SIZE/STRUCTURE',
    `content_hash`     CHAR(64)     NOT NULL COMMENT '文档内容 SHA-256',
    `split_version`    VARCHAR(64)  NOT NULL COMMENT '切分参数指纹，参与稳定 ID',
    `vector_space_id`  VARCHAR(128) NOT NULL COMMENT '向量空间标识（模型+版本+维度）',
    `chunk_count`      INT          NOT NULL DEFAULT 0 COMMENT '该版本 Chunk 数',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/INDEXING/COMPLETED/PARTIAL/FAILED/SUPERSEDED',
    `stage`            VARCHAR(24)  NOT NULL DEFAULT ''
        COMMENT 'REGISTER/EMBED_WRITE/VERIFY/CLEANUP/DELETE',
    `error_code`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '失败错误码',
    `error_message`    VARCHAR(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要',
    `attempt_count`    INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数（含首次）',
    `next_retry_at`    DATETIME(6)  NULL COMMENT '建议下次重试时间；NULL 表示不等待',
    `created_at`       DATETIME(6)  NOT NULL COMMENT '创建时间',
    `updated_at`       DATETIME(6)  NOT NULL COMMENT '状态更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vector_task_doc_version` (`document_id`, `version`),
    KEY `idx_vector_task_status_retry` (`status`, `next_retry_at`),
    KEY `idx_vector_task_status_updated` (`status`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'D31 文档向量索引任务状态机与 Outbox';

CREATE TABLE IF NOT EXISTS `vector_chunk_index` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`          VARCHAR(64)  NOT NULL COMMENT '所属文档业务标识',
    `version`              VARCHAR(64)  NOT NULL COMMENT '所属文档版本',
    `strategy`             VARCHAR(32)  NOT NULL COMMENT '切分策略',
    `chunk_id`             CHAR(36)     NOT NULL COMMENT '稳定 Chunk 标识 UUID v5',
    `chunk_index`          INT          NOT NULL COMMENT '版本/策略内序号，从 0 开始',
    `content`              MEDIUMTEXT   NOT NULL COMMENT 'Chunk 文本快照（Outbox 重放）',
    `chunk_content_hash`   CHAR(64)     NOT NULL COMMENT 'Chunk 文本 SHA-256',
    `character_count`      INT          NOT NULL COMMENT 'Chunk 字符数',
    `page_numbers`         VARCHAR(255) NOT NULL DEFAULT '' COMMENT '来源页码，逗号分隔',
    `status`               VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/INDEXED/FAILED',
    `created_at`           DATETIME(6)  NOT NULL COMMENT '创建时间',
    `updated_at`           DATETIME(6)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vector_chunk_chunk_id` (`chunk_id`),
    UNIQUE KEY `uk_vector_chunk_doc_version_index` (`document_id`, `version`, `chunk_index`),
    KEY `idx_vector_chunk_document` (`document_id`, `version`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'D31 Chunk 稳定标识、内容等价与文档关联台账';
