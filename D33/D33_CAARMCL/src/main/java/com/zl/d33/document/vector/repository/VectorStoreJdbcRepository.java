package com.zl.d33.document.vector.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d33.config.PgVectorStoreProperties;
import com.zl.d33.document.vector.config.VectorIndexProperties;
import com.zl.d33.document.vector.support.VectorStoreRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.List;

/**
 * pgvector 表的受控写入/删除/计数仓库。
 *
 * <p>为什么不用 PgVectorStore.add：现有 DocumentEmbeddingService 已经真实调用
 * EmbeddingModel 并完成维度校验；若再走 add，会二次嵌入。这里复用 Spring AI 1.1.8
 * 已核验的表结构与 ON CONFLICT 语义，只写入已算好的向量，避免重复调用与费用。</p>
 *
 * <p>SQL 与 PgVectorStore 1.1.8 字节码常量池中的语句保持一致：
 * {@code INSERT INTO ... ON CONFLICT (id) DO UPDATE ...}；表结构仍由 Spring AI
 * 负责首次初始化，本类不建表、不删除表。</p>
 */
@Repository
public class VectorStoreJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    private final String tableName;

    private final String managedBy;

    public VectorStoreJdbcRepository(@Qualifier("pgDataSource") DataSource pgDataSource,
                                     PgVectorStoreProperties properties,
                                     VectorIndexProperties indexProperties,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = new JdbcTemplate(pgDataSource);
        this.objectMapper = objectMapper;
        this.tableName = qualifiedTable(properties.getSchemaName(), properties.getTableName());
        this.managedBy = indexProperties.getManagedBy();
    }

    /**
     * 幂等 upsert：同一 UUID 重复写入只更新，不新增行。
     */
    public int upsert(VectorStoreRecord record) {
        if (record == null || record.getId() == null || record.getEmbedding() == null) {
            throw new IllegalArgumentException("向量写入记录不完整");
        }
        String sql = "INSERT INTO " + tableName
                + " (id, content, metadata, embedding) VALUES (?::uuid, ?, ?::jsonb, ?::vector)"
                + " ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content,"
                + " metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding";
        return jdbcTemplate.update(sql,
                record.getId(),
                record.getContent(),
                toJson(record),
                vectorLiteral(record.getEmbedding()));
    }

    /**
     * 按 UUID 删除，返回真实删除行数；不存在时返回 0，不报错。
     */
    public int deleteByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            deleted += jdbcTemplate.update("DELETE FROM " + tableName + " WHERE id = ?::uuid", id);
        }
        return deleted;
    }

    /**
     * 统计给定 UUID 中真实存在的向量行数，用于写入后的数据库侧核对。
     */
    public int countByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Integer matched = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + tableName + " WHERE id = ?::uuid", Integer.class, id);
            count += matched == null ? 0 : matched;
        }
        return count;
    }

    /**
     * 返回给定 UUID 中在 PostgreSQL 里缺失的 ID，用于补偿/重试前的精确核对。
     */
    public List<String> findMissingIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> missing = new java.util.ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Integer matched = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + tableName + " WHERE id = ?::uuid", Integer.class, id);
            if (matched == null || matched == 0) {
                missing.add(id);
            }
        }
        return missing;
    }

    /**
     * 列出 管理的全部向量 ID，用于全局孤儿检查。
     */
    public List<String> listManagedIds() {
        return jdbcTemplate.queryForList(
                "SELECT id::text FROM " + tableName + " WHERE metadata->>'managedBy' = ?",
                String.class, managedBy);
    }

    /**
     * 列出指定文档的 管理向量 ID。
     */
    public List<String> listManagedIdsByDocument(String documentId) {
        return jdbcTemplate.queryForList(
                "SELECT id::text FROM " + tableName
                        + " WHERE metadata->>'managedBy' = ? AND metadata->>'documentId' = ?",
                String.class, managedBy, documentId);
    }

    /**
     * 统计指定文档的 管理向量数量。
     */
    public int countManagedByDocument(String documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName
                        + " WHERE metadata->>'managedBy' = ? AND metadata->>'documentId' = ?",
                Integer.class, managedBy, documentId);
        return count == null ? 0 : count;
    }

    /**
     * 删除指定文档的全部 管理向量，返回真实删除行数。
     */
    public int deleteManagedByDocument(String documentId) {
        return jdbcTemplate.update(
                "DELETE FROM " + tableName
                        + " WHERE metadata->>'managedBy' = ? AND metadata->>'documentId' = ?",
                managedBy, documentId);
    }

    private String toJson(VectorStoreRecord record) {
        try {
            return objectMapper.writeValueAsString(record.getMetadata());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("向量 metadata 无法序列化为 JSON", e);
        }
    }

    private String vectorLiteral(float[] vector) {
        if (vector.length == 0) {
            throw new IllegalArgumentException("向量维度为 0");
        }
        StringBuilder literal = new StringBuilder(vector.length * 16);
        literal.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            if (Float.isNaN(vector[i]) || Float.isInfinite(vector[i])) {
                throw new IllegalArgumentException("向量包含 NaN/Infinity，拒绝写入");
            }
            literal.append(Float.toString(vector[i]));
        }
        return literal.append(']').toString();
    }

    private String qualifiedTable(String schema, String table) {
        if (!safeIdentifier(schema) || !safeIdentifier(table)) {
            throw new IllegalArgumentException("pgvector schema/table 名称非法");
        }
        return schema + "." + table;
    }

    private boolean safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }
}
