package com.zl.d34.rag.model;

import java.util.List;

/**
 * 同一事实、同一设备型号与工况下的有效证据冲突。
 */
public record ConflictGroup(String factKey,
                            String scope,
                            List<String> evidenceIds,
                            List<String> values,
                            String description) {
    public ConflictGroup {
        evidenceIds = List.copyOf(evidenceIds);
        values = List.copyOf(values);
    }
}
