package com.zl.d34.rag.service;

import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.service.AiChatResult;
import com.zl.d34.service.AiChatService;
import com.zl.d34.service.ChatInvocation;
import com.zl.d34.service.ThinkingSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 生成服务：Prompt 构造 → ChatModel（经 AiChatService 唯一抽象）→ 生成回答。
 *
 * <p>硬性边界：<b>禁止直接访问 VectorStore</b>，不接触任何检索/向量对象；
 * 检索资料以「已组装的上下文块文本」进入本服务，且被系统约束标注为不可信数据。</p>
 *
 * <p>Prompt Injection 防护结构：系统指令（安全约束）置于系统提示词
 * {@link ChatInvocation#systemPrompt()}，用户问题保持独立用户消息，检索资料
 * 以不可信数据标注附加于系统提示词之后——资料中的指令性文字无法覆盖系统指令，
 * 模型位置约定不混淆。</p>
 */
@Slf4j
@Service
public class GenerationService {

    private final AiChatService aiChatService;

    private final RagProperties properties;

    public GenerationService(AiChatService aiChatService,
                             RagProperties properties) {
        this.aiChatService = aiChatService;
        this.properties = properties;
    }

    /**
     * 构造 Prompt 并调用模型生成回答。
     *
     * @param query        用户问题（原文）
     * @param contextBlock 引用注册表生成的受约束上下文块（含 [S编号] 分配与不可信数据声明）
     * @return 模型结构化结果（原始内容未清洗，引用清洗由编排器完成）
     * @throws RuntimeException 模型调用异常（超时/上游 5xx 等）原样抛出，由编排器归类 GENERATION_FAILED
     */
    public AiChatResult generate(String query, String contextBlock) {
        String systemPrompt = properties.getSystemPrompt() + "\n\n" + contextBlock;
        ChatInvocation invocation = new ChatInvocation(systemPrompt, query, List.of());
        AiChatResult result = aiChatService.chat(invocation, ThinkingSettings.legacyDefault());
        log.info("RAG 生成完成：mode={}，model={}，mocked={}，answerChars={}",
                result.mode(), result.model(), result.mocked(),
                result.content() == null ? 0 : result.content().length());
        return result;
    }
}
