package com.zl.d31.document.vector.controller;

import com.zl.d31.common.Result;
import com.zl.d31.document.chunk.model.ChunkStrategy;
import com.zl.d31.document.vector.dto.VectorChunkView;
import com.zl.d31.document.vector.dto.VectorIndexConsistencyView;
import com.zl.d31.document.vector.dto.VectorIndexTaskView;
import com.zl.d31.document.vector.service.DocumentVectorIndexService;
import com.zl.d31.document.vector.service.VectorIndexConsistencyService;
import com.zl.d31.document.vector.support.VectorIndexConverter;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * D31 向量索引入口：建立/查询/重试/一致性核对/补偿，均返回统一 Result。
 */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/vector-index")
public class DocumentVectorIndexController {

    private final DocumentVectorIndexService vectorIndexService;

    private final VectorIndexConsistencyService consistencyService;

    private final VectorIndexConverter converter;

    public DocumentVectorIndexController(DocumentVectorIndexService vectorIndexService,
                                         VectorIndexConsistencyService consistencyService,
                                         VectorIndexConverter converter) {
        this.vectorIndexService = vectorIndexService;
        this.consistencyService = consistencyService;
        this.converter = converter;
    }

    /** 建立/确保当前版本向量索引；重复执行命中 COMPLETED 时返回 duplicate=true。 */
    @PostMapping
    public Result<VectorIndexTaskView> index(
            @PathVariable("documentId") String documentId,
            @RequestParam(value = "strategy", defaultValue = "FIXED_SIZE") ChunkStrategy strategy) {
        return Result.success(vectorIndexService.indexDocument(documentId, strategy));
    }

    /** 查询当前版本任务状态；未建立时 data 为 null。 */
    @GetMapping
    public Result<VectorIndexTaskView> status(@PathVariable("documentId") String documentId) {
        return Result.success(vectorIndexService.currentTask(documentId));
    }

    /** 查询当前文档全部 Chunk 台账（按版本/序号升序）。 */
    @GetMapping("/chunks")
    public Result<List<VectorChunkView>> chunks(@PathVariable("documentId") String documentId,
                                                @RequestParam("version") String version) {
        return Result.success(vectorIndexService.listChunks(documentId, version).stream()
                .map(converter::toView).toList());
    }

    /** 人工重试当前版本：复用稳定 ID，只补失败/缺失 Chunk。 */
    @PostMapping("/retry")
    public Result<VectorIndexTaskView> retry(@PathVariable("documentId") String documentId,
                                             @RequestParam("version") String version) {
        return Result.success(vectorIndexService.retry(documentId, version));
    }

    /** 仅删除文档向量与台账；文档/页面保留，供单独清理。 */
    @DeleteMapping
    public Result<?> deleteVectors(@PathVariable("documentId") String documentId) {
        return Result.success(vectorIndexService.deleteVectorsOnly(documentId));
    }

    /** 文档级一致性核对：台账 vs PostgreSQL 实际行。 */
    @GetMapping("/consistency")
    public Result<VectorIndexConsistencyView> consistency(@PathVariable("documentId") String documentId) {
        return Result.success(consistencyService.checkDocument(documentId));
    }

    /** 文档级补偿：删孤儿 + 只重试缺失 Chunk。 */
    @PostMapping("/consistency/repair")
    public Result<VectorIndexTaskView> repair(@PathVariable("documentId") String documentId) {
        return Result.success(consistencyService.repair(documentId));
    }
}
