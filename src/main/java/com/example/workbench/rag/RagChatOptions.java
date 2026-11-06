package com.example.workbench.rag;

/**
 * Per-request retrieval switches. They never mutate the service-wide configuration.
 *
 * @param queryRewriteEnabled 是否启用问题改写
 * @param multiQueryEnabled 是否启用多查询召回
 * @param webSearchAllowed 本次请求是否允许联网搜索兜底（评测链路置 false，只衡量本地知识库能力）
 * @param qualityGateAllowed 本次请求是否允许答案接地质量门裁决（评测链路置 false：
 *        质量门是给在线聊天防幻觉的额外保险，其 LLM 裁决存在不确定性，
 *        评测要衡量的是「检索→回答」基线能力，不应被裁判的抖动污染指标）
 */
public record RagChatOptions(
        boolean queryRewriteEnabled,
        boolean multiQueryEnabled,
        boolean webSearchAllowed,
        boolean qualityGateAllowed) {

    /** 默认允许联网兜底与质量门裁决，保持既有调用方的语义不变。 */
    public RagChatOptions(boolean queryRewriteEnabled, boolean multiQueryEnabled) {
        this(queryRewriteEnabled, multiQueryEnabled, true);
    }

    /** 兼容只关心联网开关的调用方，质量门默认开启。 */
    public RagChatOptions(boolean queryRewriteEnabled, boolean multiQueryEnabled, boolean webSearchAllowed) {
        this(queryRewriteEnabled, multiQueryEnabled, webSearchAllowed, true);
    }
}
