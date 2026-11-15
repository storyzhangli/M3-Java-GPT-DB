-- ============================================================================
-- D31 迁移：把 knowledge_document 的全局 content_hash 唯一约束
--           改为 (document_id, content_hash) 复合唯一。
--
-- 背景：
--   旧约束 uk_knowledge_content_hash(content_hash) 会让“不同文档、相同内容”
--   在插入时触发重复键，无法满足 D31“不同文档即使内容相同也能区分归属”。
--
-- 影响：
--   仅修改约束，不删除、不清空任何业务数据；
--   执行前请确认目标库没有同 document_id 的重复 content_hash 行。
--   D31 开发库当前 knowledge_document 为 0 行，可直接执行。
--
-- 幂等：
--   通过 information_schema 判断旧索引是否存在；存在才 DROP，缺新索引才 ADD。
-- ============================================================================

SET @has_old = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_document'
      AND index_name = 'uk_knowledge_content_hash'
);

SET @sql = IF(@has_old > 0,
    'ALTER TABLE `knowledge_document` DROP INDEX `uk_knowledge_content_hash`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_new = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_document'
      AND index_name = 'uk_document_content_hash'
);

SET @sql = IF(@has_new = 0,
    'ALTER TABLE `knowledge_document` ADD UNIQUE KEY `uk_document_content_hash` (`document_id`, `content_hash`)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 复核输出：应看到 uk_document_content_hash，且不再有 uk_knowledge_content_hash。
SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'knowledge_document'
ORDER BY index_name, seq_in_index;
