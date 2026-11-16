-- ============================================================================
-- 文档页面级提取结果 DDL（MySQL 8.x，InnoDB，utf8mb4）
-- 本文件已按 D31 真机表结构对齐：character_count / blank_page / parser_*。
-- 页面表不保存 source/title/version；这些元数据由文档表统一维护，
-- 页面服务读取时从 knowledge_document 回填，避免两处版本不一致。
-- 可安全重复执行：CREATE TABLE IF NOT EXISTS，不删除或覆盖任何已有数据。
-- 库：ai_db
-- ============================================================================

CREATE TABLE IF NOT EXISTS `knowledge_document_page` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`      VARCHAR(64) NOT NULL COMMENT '关联 knowledge_document.document_id',
    `page_number`      INT         NOT NULL COMMENT '物理页序号，从 1 开始',
    `content`          LONGTEXT    NOT NULL COMMENT '该页提取文本',
    `character_count`  INT         NOT NULL COMMENT '该页文本字符数（领域字段 contentLength）',
    `blank_page`       TINYINT(1)  NOT NULL COMMENT '是否空白页：1 空白，0 含可用文字',
    `parser_name`      VARCHAR(64) NOT NULL COMMENT '解析器名称',
    `parser_version`   VARCHAR(32) NOT NULL COMMENT '解析器版本',
    `updated_at`       DATETIME(6) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_document_page` (`document_id`, `page_number`),
    KEY `idx_knowledge_document_page_doc` (`document_id`, `page_number`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文档页面级提取结果表（D27 来源定位）';
