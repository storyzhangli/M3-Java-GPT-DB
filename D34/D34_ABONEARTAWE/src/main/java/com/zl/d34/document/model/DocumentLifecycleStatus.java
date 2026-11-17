package com.zl.d34.document.model;

/**
 * 文档生效生命周期，与导入处理状态 {@link DocumentStatus} 严格分离。
 */
public enum DocumentLifecycleStatus {

    /** 已登记或尚未到生效时间，不可作为回答证据。 */
    PENDING,

    /** 当前生效，可在有效时间区间内作为证据。 */
    ACTIVE,

    /** 已废弃，不可作为回答证据。 */
    DEPRECATED;

    public static DocumentLifecycleStatus fromName(String value, DocumentLifecycleStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
