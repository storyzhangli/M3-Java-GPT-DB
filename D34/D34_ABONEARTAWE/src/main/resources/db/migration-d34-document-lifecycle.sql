-- ============================================================================
-- D34 文档生命周期增量迁移（MySQL 8.x）
--
-- 仅新增生命周期字段与索引，不删除、不清空、不改写既有文档正文或向量。
-- 既有行通过 ADD COLUMN 的 ACTIVE 默认值保持 D33 检索兼容；应用内新建/处理中
-- 文档会显式写入 PENDING，解析完成后才转为 ACTIVE。
-- 幂等：每列/索引均先查 information_schema，存在则跳过。
-- ============================================================================

SET @has_lifecycle = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'knowledge_document'
      AND column_name = 'lifecycle_status'
);
SET @sql = IF(@has_lifecycle = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `lifecycle_status` VARCHAR(16) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''PENDING/ACTIVE/DEPRECATED；与导入状态分离'' AFTER `status`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_effective_from = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'knowledge_document'
      AND column_name = 'effective_from'
);
SET @sql = IF(@has_effective_from = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `effective_from` DATETIME(6) NULL COMMENT ''生效起始时间'' AFTER `lifecycle_status`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_effective_to = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'knowledge_document'
      AND column_name = 'effective_to'
);
SET @sql = IF(@has_effective_to = 0,
    'ALTER TABLE `knowledge_document` ADD COLUMN `effective_to` DATETIME(6) NULL COMMENT ''失效时间'' AFTER `effective_from`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_lifecycle_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'knowledge_document'
      AND index_name = 'idx_knowledge_doc_lifecycle'
);
SET @sql = IF(@has_lifecycle_index = 0,
    'ALTER TABLE `knowledge_document` ADD KEY `idx_knowledge_doc_lifecycle` (`lifecycle_status`, `effective_from`, `effective_to`)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'knowledge_document'
  AND column_name IN ('lifecycle_status', 'effective_from', 'effective_to')
ORDER BY ordinal_position;
