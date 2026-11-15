package com.zl.d32.document.search.exception;

/**
 * 相似度检索业务异常：携带可分类的错误类型，由全局异常处理器转换为统一 Result。
 */
public class VectorSearchException extends RuntimeException {

    private final boolean badRequest;

    private VectorSearchException(String message, boolean badRequest) {
        super(message);
        this.badRequest = badRequest;
    }

    /** 参数类错误（空查询、K 越界等），对应 400 语义。 */
    public static VectorSearchException badRequest(String message) {
        return new VectorSearchException(message, true);
    }

    /** 检索执行类错误（Embedding 失败、向量库查询失败等），对应 500 语义。 */
    public static VectorSearchException execution(String message) {
        return new VectorSearchException(message, false);
    }

    public boolean isBadRequest() {
        return badRequest;
    }
}
