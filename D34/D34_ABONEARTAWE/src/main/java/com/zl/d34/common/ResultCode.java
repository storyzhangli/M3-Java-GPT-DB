package com.zl.d34.common;

import lombok.Getter;

/**
 * 统一业务返回码。
 *
 * <p>HTTP 层统一返回 200，调用结果通过该业务码区分，保持 {@code code/msg/data} 契约稳定。</p>
 */
@Getter
public enum ResultCode {

    /** 成功。 */
    SUCCESS(200, "success"),

    /** 请求参数校验失败（空白、超长、报文不可读等）。 */
    PARAM_INVALID(4001, "请求参数校验失败"),

    /** AI 配置/鉴权/客户端错误：缺失或占位密钥、上游 4xx（含 401）。 */
    AI_CONFIG_ERROR(5001, "AI 服务配置或鉴权异常，请检查密钥与参数配置"),

    /** AI 服务端暂不可用：上游 5xx/超时/限流且重试耗尽。 */
    AI_UNAVAILABLE(5002, "AI 服务暂不可用，请稍后重试"),

    /** 未预期的系统兜底错误。 */
    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试"),

    /** 知识库无任何检索结果。 */
    NO_EVIDENCE(4401, "知识库无可用证据"),

    /** 仅词面相似，证据不能支持问题。 */
    LOW_RELEVANCE(4402, "检索证据相关性不足"),

    /** 有相关证据但覆盖不足或没有当前生效证据。 */
    INSUFFICIENT_EVIDENCE(4403, "证据覆盖不足"),

    /** 缺设备型号、故障码等必要条件。 */
    MISSING_REQUIRED_CONTEXT(4404, "缺少必要上下文"),

    /** 当前有效证据对同一事实给出矛盾内容。 */
    EVIDENCE_CONFLICT(4405, "有效证据存在冲突"),

    /** 引用不在白名单或格式非法。 */
    INVALID_CITATION(4406, "引用编号或格式非法"),

    /** 输出含无证据支持的设备事实。 */
    UNSUPPORTED_CLAIM(4407, "设备事实缺少证据支持"),

    /** 其他生成后校验失败。 */
    GENERATION_VALIDATION_FAILED(4408, "生成结果校验失败");

    private final Integer code;

    private final String msg;

    ResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
