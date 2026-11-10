package com.example.workbench.rag;

/**
 * Per-request retrieval switches. They never mutate the service-wide configuration.
 *
 * @param queryRewriteEnabled 是否启用问题改写
 * @param multiQueryEnabled 是否启用多查询召回
 * @param webSearchAllowed 本次请求是否允许联网搜索兜底（评测链路置 false，只衡量本地知识库能力）
 * @param qualityGateAllowed 本次请求是否允许质量门 LLM 裁决（评测链路置 false：
 *        同时旁路召回候选筛选 relevantSources 与答案接地裁决 approvesAnswer 两处 LLM 判定，
 *        只保留本地规则（阈值/重排/强信号）过滤；质量门是给在线聊天防幻觉的额外保险，
 *        其 LLM 裁决存在不确定性，评测要衡量的是「检索→回答」基线能力，
 *        不应被裁判的抖动污染指标）
 * @param modelFallbackAllowed 本地无证据时是否允许模型兜底生成（评测链路对
 *        expectNoAnswer / requireLocalEvidence 用例置 false：sources 为空直接返回
 *        固定拒答文案 NO_CONTEXT_ANSWER。模型兜底的回答不可控——有时拒答、有时幻觉、
 *        有时复述召回文本，会让「应拒答」类评测随措辞抖动；禁用后拒答成为确定性事件，
 *        SECURITY / NO_ANSWER 套件结果才可复现）
 */
public record RagChatOptions(
        boolean queryRewriteEnabled,
        boolean multiQueryEnabled,
        boolean webSearchAllowed,
        boolean qualityGateAllowed,
        boolean modelFallbackAllowed) {

    /** 默认允许联网兜底与质量门裁决，保持既有调用方的语义不变。 */
    public RagChatOptions(boolean queryRewriteEnabled, boolean multiQueryEnabled) {
        this(queryRewriteEnabled, multiQueryEnabled, true);
    }

    /** 兼容只关心联网开关的调用方，质量门默认开启。 */
    public RagChatOptions(boolean queryRewriteEnabled, boolean multiQueryEnabled, boolean webSearchAllowed) {
        this(queryRewriteEnabled, multiQueryEnabled, webSearchAllowed, true);
    }

    /** 兼容只关心联网与质量门开关的调用方，模型兜底默认允许。 */
    public RagChatOptions(boolean queryRewriteEnabled, boolean multiQueryEnabled, boolean webSearchAllowed,
            boolean qualityGateAllowed) {
        this(queryRewriteEnabled, multiQueryEnabled, webSearchAllowed, qualityGateAllowed, true);
    }
}
