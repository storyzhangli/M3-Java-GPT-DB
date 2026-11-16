package com.zl.d33.rag.controller;

import com.zl.d33.common.Result;
import com.zl.d33.common.ResultCode;
import com.zl.d33.rag.dto.RagQueryRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.service.RagOrchestrator;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 最小闭环入口：文档导入 → 向量检索 → 上下文组装 → 大模型回答（带来源引用）。
 *
 * <p>请求体：{@code {"query": "...", "topK": 5, "documentId": "..."}}；
 * topK 缺省取 app.ai.rag.default-top-k（5）；documentId 可选过滤。
 * 响应为 Result&lt;RagResponse&gt;，RagResponse 含回答、来源列表、检索/生成状态字段。</p>
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final RagOrchestrator orchestrator;

    public RagController(RagOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** RAG 问答：空检索/无可用上下文直接受控拒答且不调用生成模型。 */
    @PostMapping("/ask")
    public Result<RagResponse> ask(@Valid @RequestBody RagQueryRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            return Result.fail(ResultCode.PARAM_INVALID);
        }
        return Result.success(orchestrator.ask(request));
    }
}
