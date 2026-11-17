package com.zl.d34.document.support;

import com.zl.d34.document.chunk.config.ChunkProperties;
import com.zl.d34.document.chunk.model.ChunkStrategy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 稳定标识生成器：文档 ID、切分版本、Chunk UUID v5。
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>同一文档、同一内容、同一版本与同一策略，重复执行生成完全相同的标识；</li>
 *   <li>不同来源或不同文档标识不会被内容相同合并；</li>
 *   <li>内容变化通过版本递增体现，Chunk ID 不跨版本复用；</li>
 *   <li>切分参数变化进入 splitVersion，避免旧切分结果被误当作同一批次。</li>
 * </ul>
 *
 * <p>文档 ID 采用 SHA-256 前 128 bit（32 个十六进制字符），兼容既有 VARCHAR(64)；
 * Chunk ID 使用 RFC 4122 UUID v5（SHA-1 + 固定命名空间），可直接作为
 * Spring AI PgVectorStore 的 UUID 主键。</p>
 */
@Component
public class StableIdGenerator {

    /** 专用 UUID v5 命名空间；固定值，不得随运行环境变化。 */
    private static final UUID D34_NAMESPACE =
            UUID.fromString("7f1e6b2a-3c4d-5e6f-8a9b-0c1d2e3f4a5b");

    private static final String DOC_PREFIX = "D34|document|";

    private static final String FILE_PREFIX = "D34|file|";

    private static final String SPLIT_PREFIX = "D34|split|v1|";

    /**
     * 生成稳定文档 ID：来源 + 文档业务标识/标题。
     *
     * <p>调用方若提供外部 documentId，应直接使用外部值，不再调用本方法；
     * 本方法用于没有外部标识时，从业务身份派生稳定值。</p>
     */
    public String documentId(String source, String title) {
        String normalizedSource = normalize(source);
        String normalizedTitle = normalize(title);
        return sha256(DOC_PREFIX + normalizedSource + "|" + normalizedTitle).substring(0, 32);
    }

    /**
     * 无来源/标题的兼容路径：按文件内容指纹生成文档 ID。
     *
     * <p>该路径无法区分“内容相同的不同文档”，仅在没有业务身份时使用；
     * 正式导入应提供 source/title 或显式 documentId。</p>
     */
    public String fileDocumentId(String contentHash) {
        return sha256(FILE_PREFIX + normalize(contentHash)).substring(0, 32);
    }

    /**
     * 切分版本：策略 + 全部切分参数 + 算法版本。
     */
    public String splitVersion(ChunkStrategy strategy, ChunkProperties properties) {
        String raw = SPLIT_PREFIX
                + (strategy == null ? "" : strategy.name()) + "|"
                + properties.getChunkSize() + "|"
                + properties.getChunkOverlap() + "|"
                + properties.getMinChunkSize() + "|"
                + properties.getMaxChunkSize() + "|"
                + properties.getBoundaryTolerance() + "|"
                + properties.isMergeShortBlocks() + "|"
                + properties.getLengthUnit();
        return sha256(raw).substring(0, 16);
    }

    /**
     * 生成 Chunk 稳定 UUID v5。
     *
     * <p>命名空间包含 documentId、文档版本、策略、切分版本、chunkIndex 与内容指纹；
     * 任一变化都会得到新 ID，但重试或重复导入同一输入恒得同一 ID。</p>
     */
    public String chunkId(String documentId, String version, ChunkStrategy strategy,
                          String splitVersion, int chunkIndex, String contentHash) {
        String name = normalize(documentId) + "|" + normalize(version) + "|"
                + (strategy == null ? "" : strategy.name()) + "|"
                + normalize(splitVersion) + "|" + chunkIndex + "|" + normalize(contentHash);
        return uuidV5(name);
    }

    /**
     * 内容 SHA-256 小写十六进制，用于文档/Chunk 指纹。
     */
    public String contentHash(String content) {
        return sha256(content == null ? "" : content);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前环境不支持 SHA-256", e);
        }
    }

    private String uuidV5(String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] namespaceBytes = toBytes(D34_NAMESPACE);
            sha1.update(namespaceBytes);
            byte[] digest = sha1.digest(name.getBytes(StandardCharsets.UTF_8));

            // RFC 4122：version = 5，variant = IETF (10xx)。
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);

            StringBuilder uuid = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) {
                    uuid.append('-');
                }
                uuid.append(String.format("%02x", digest[i]));
            }
            return uuid.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前环境不支持 SHA-1", e);
        }
    }

    private byte[] toBytes(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (least >>> (8 * (7 - i)));
        }
        return bytes;
    }

    private String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
