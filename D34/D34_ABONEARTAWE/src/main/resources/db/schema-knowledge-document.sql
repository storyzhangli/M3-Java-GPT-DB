-- ============================================================================
-- 知识文档登记与导入表 DDL（MySQL 8.x，InnoDB，utf8mb4）
-- 本文件已按 D31 真机表结构对齐：source_system / file_size / version varchar。
-- 可安全重复执行：CREATE TABLE IF NOT EXISTS，不删除或覆盖任何已有数据。
-- 库：ai_db
--
-- D31 关键约束变更：
--   旧库的 uk_knowledge_content_hash(content_hash) 为全局唯一，会把“不同文档、
--   内容相同”误判为同一份；D31 改为 (document_id, content_hash) 复合唯一。
--   已有环境请先执行 migration-d31-content-hash-scope.sql，再按本 DDL 复核。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`    VARCHAR(64)  NOT NULL COMMENT '文档业务标识；D31 由 source+title 稳定派生或外部指定',
    `source_system`  VARCHAR(128) NOT NULL COMMENT '文档来源（领域字段 source）',
    `title`          VARCHAR(300) NOT NULL COMMENT '文档标题，安全截断',
    `version`        VARCHAR(64)  NOT NULL COMMENT '文档版本，从 1 开始；沿用既有字符串列',
    `status`         VARCHAR(16)  NOT NULL COMMENT 'PENDING/PROCESSING/COMPLETED/FAILED',
    `lifecycle_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/DEPRECATED；与导入状态分离',
    `effective_from` DATETIME(6)  NULL COMMENT '生效起始时间',
    `effective_to`   DATETIME(6)  NULL COMMENT '失效时间',
    `file_name`      VARCHAR(255) NULL COMMENT '原始文件名',
    `content_type`   VARCHAR(128) NULL COMMENT '原始内容类型',
    `file_size`      BIGINT       NULL COMMENT '原始字节长度（领域字段 contentLength）',
    `content_hash`   CHAR(64)     NULL COMMENT '原始字节 SHA-256；登记态为 NULL',
    `error_code`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '失败错误码',
    `error_message`  VARCHAR(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要，不含堆栈与密钥',
    `created_at`     DATETIME(6)  NOT NULL COMMENT '创建时间',
    `updated_at`     DATETIME(6)  NOT NULL COMMENT '状态更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_document_id` (`document_id`),
    UNIQUE KEY `uk_document_content_hash` (`document_id`, `content_hash`),
    KEY `idx_knowledge_doc_status` (`status`, `updated_at`),
    KEY `idx_knowledge_doc_lifecycle` (`lifecycle_status`, `effective_from`, `effective_to`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识文档表（D26 登记与导入，D31 稳定身份）';
