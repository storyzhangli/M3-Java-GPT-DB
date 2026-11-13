-- ============================================================================
-- D30 PostgreSQL + pgvector 数据库能力验证脚本
-- 目标库：spring_ai
-- 说明：本脚本只操作独立测试表 d30_pgvector_test，不修改任何业务表。
-- 测试向量为确定性合成数据，仅用于维度/距离/索引验证，不代表真实 Embedding 输出。
-- ============================================================================

-- 1. 环境与扩展确认
SELECT current_database();
SELECT version();

SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';

-- 2. 独立测试表：必须为 vector(384)
CREATE TABLE IF NOT EXISTS public.d30_pgvector_test (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    embedding VECTOR(384) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

SELECT format_type(atttypid, atttypmod) AS embedding_type
FROM pg_attribute
WHERE attrelid = 'public.d30_pgvector_test'::regclass
  AND attname = 'embedding';

-- 3. HNSW cosine 索引
CREATE INDEX IF NOT EXISTS idx_d30_pgvector_test_embedding
ON public.d30_pgvector_test
USING hnsw (embedding vector_cosine_ops);

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'd30_pgvector_test';

-- 4. 写入两条 384 维合成向量
INSERT INTO public.d30_pgvector_test (content, embedding)
VALUES (
    'D30 SQL synthetic vector A',
    ('[' || array_to_string(ARRAY(SELECT 0.1::real FROM generate_series(1, 384)), ',') || ']')::vector
);

INSERT INTO public.d30_pgvector_test (content, embedding)
VALUES (
    'D30 SQL synthetic vector B',
    ('[' || array_to_string(
        ARRAY(SELECT CASE WHEN n = 1 THEN 1.0::real ELSE 0.0::real END
              FROM generate_series(1, 384) AS n),
        ','
    ) || ']')::vector
);

-- 5. 读取并确认落库维度
SELECT id, content, vector_dims(embedding) AS dimensions
FROM public.d30_pgvector_test
ORDER BY id DESC
LIMIT 2;

-- 6. cosine 距离查询：<=> 返回 distance，越小越相似
SELECT
    id,
    content,
    embedding <=> (
        '[' || array_to_string(ARRAY(SELECT 0.1::real FROM generate_series(1, 384)), ',') || ']'
    )::vector AS cosine_distance,
    1 - (
        embedding <=> (
            '[' || array_to_string(ARRAY(SELECT 0.1::real FROM generate_series(1, 384)), ',') || ']'
        )::vector
    ) AS cosine_similarity
FROM public.d30_pgvector_test
ORDER BY cosine_distance
LIMIT 5;

-- 7. 执行计划：小数据量可能选择 Seq Scan，不代表索引无效
EXPLAIN
SELECT id, content, embedding <=> (
    '[' || array_to_string(ARRAY(SELECT 0.1::real FROM generate_series(1, 384)), ',') || ']'
)::vector AS distance
FROM public.d30_pgvector_test
ORDER BY distance
LIMIT 5;

-- 8. 错误维度负向验证（单独执行，预期报错；不要放进自动成功脚本）
-- INSERT INTO public.d30_pgvector_test (content, embedding)
-- VALUES (
--     'D30 invalid 383 dimensions',
--     ('[' || array_to_string(ARRAY(SELECT 0.1::real FROM generate_series(1, 383)), ',') || ']')::vector
-- );
-- 预期：ERROR: expected 384 dimensions, not 383
