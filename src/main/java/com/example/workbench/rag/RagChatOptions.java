package com.example.workbench.rag;

/**
 * Per-request retrieval switches. They never mutate the service-wide configuration.
 *
 * @param queryRewriteEnabled 是否启用问题改写
 * @param multiQueryEnabled 是否启用多查询召回
 * @param webSearchAllowed 本次请求是否允许联网搜索兜底（评测链路置 false，只衡量本地知识库能力）
 */
public record RagChatOptions(boolean queryRewriteEnabled, boolean multiQueryEnabled, boolean webSearchAllowed) {

    /** 默认允许联网兜底，保持既有调用方的语义不变。 */
    public RagChatOptions(boolean queryRewriteEnabled, boolean multiQueryEnabled) {
        this(queryRewriteEnabled, multiQueryEnabled, true);
    }
}
