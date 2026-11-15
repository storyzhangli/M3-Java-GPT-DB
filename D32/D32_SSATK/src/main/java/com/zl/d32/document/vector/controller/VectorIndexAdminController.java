package com.zl.d32.document.vector.controller;

import com.zl.d32.common.Result;
import com.zl.d32.document.vector.dto.VectorDeleteView;
import com.zl.d32.document.vector.dto.VectorIndexConsistencyView;
import com.zl.d32.document.vector.service.DocumentDeletionService;
import com.zl.d32.document.vector.service.VectorIndexConsistencyService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 文档删除与全局一致性入口。
 */
@RestController
@RequestMapping("/api/v1")
public class VectorIndexAdminController {

    private final DocumentDeletionService documentDeletionService;

    private final VectorIndexConsistencyService consistencyService;

    public VectorIndexAdminController(DocumentDeletionService documentDeletionService,
                                      VectorIndexConsistencyService consistencyService) {
        this.documentDeletionService = documentDeletionService;
        this.consistencyService = consistencyService;
    }

    /** 文档删除：先 PG 向量、后 MySQL 台账/页面/文档；失败时保留可重试状态。 */
    @DeleteMapping("/documents/{documentId}")
    public Result<VectorDeleteView> deleteDocument(@PathVariable("documentId") String documentId) {
        return Result.success(documentDeletionService.deleteDocument(documentId));
    }

    /** 全局一致性：台账 vs PG 中 managedBy=d32 的向量。 */
    @GetMapping("/vector-index/consistency")
    public Result<VectorIndexConsistencyView> globalConsistency() {
        return Result.success(consistencyService.checkGlobal());
    }

    /** 全局孤儿清理：只删 PG 中不属于任何台账的 D32 向量。 */
    @PostMapping("/vector-index/consistency/orphans")
    public Result<Map<String, Integer>> cleanGlobalOrphans() {
        return Result.success(Map.of("deleted", consistencyService.repairGlobalOrphans()));
    }
}
