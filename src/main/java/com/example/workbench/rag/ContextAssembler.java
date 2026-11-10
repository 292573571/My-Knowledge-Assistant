package com.example.workbench.rag;

import com.example.workbench.tools.WebSearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 上下文装配：Token 估算、预算截断、片段格式化与不可信边界转义。
 *
 * <p>从 {@code RagService} 外提的第三步（D-2）。全部为纯函数，不持有任何状态，
 * 同步链路与流式链路共用同一份实现，避免两条链路对「预算怎么算、边界怎么转义」产生分叉。</p>
 *
 * <p>约束：本次只搬运代码，不修改任何阈值、长度常量或截断口径。</p>
 */
final class ContextAssembler {

    /** 知识片段起始边界标记，prompt 中以此声明「以下内容不可信」。 */
    static final String BEGIN_MARKER = "<<<BEGIN_UNTRUSTED_";
    /** 知识片段结束边界标记。 */
    static final String END_MARKER = "<<<END_UNTRUSTED_";

    private static final int HISTORY_MESSAGE_MAX_CHARS = 600;

    private ContextAssembler() {
    }

    /**
     * 估算文本的 token 数：汉字按 1.5 字/token，其余按 4 字符/token。
     *
     * @param text 待估算文本
     * @return 估算 token 数，空文本为 0
     */
    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int han = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                han++;
            }
            offset += Character.charCount(codePoint);
        }
        return (int) Math.ceil(han / 1.5 + (text.length() - han) / 4.0);
    }

    /**
     * 按 token 预算截断单片段，并补上结束边界标记，避免截断后边界不闭合导致提示词注入。
     *
     * @param text        原始片段
     * @param tokenBudget token 预算
     * @return 截断后的片段
     */
    static String truncateToTokenBudget(String text, int tokenBudget) {
        int maxChars = Math.min(text.length(), tokenBudget * 2);
        String truncated = text.substring(0, maxChars)
                .replace(END_MARKER + "KNOWLEDGE_CHUNK>>>", "");
        return truncated + "\n[上下文已按 Token 预算截断]\n" + END_MARKER + "KNOWLEDGE_CHUNK>>>";
    }

    /**
     * 把单个知识片段格式化为带元数据的不可信块。
     *
     * @param document 知识片段
     * @return 格式化后的块文本
     */
    static String formatChunk(SourceDocument document) {
        return """
                <<<BEGIN_UNTRUSTED_KNOWLEDGE_CHUNK>>>
                source: %s
                path: %s
                documentId: %s
                chunkIndex: %d
                pageNumber: %s
                title: %s
                headingPath: %s
                chunkType: %s
                content:
                %s
                <<<END_UNTRUSTED_KNOWLEDGE_CHUNK>>>
                """.formatted(
                escapeMarkers(document.source()),
                escapeMarkers(document.path()),
                escapeMarkers(document.documentId()),
                document.chunkIndex(),
                document.pageNumber() > 0 ? document.pageNumber() : "-",
                escapeMarkers(document.title()),
                escapeMarkers(document.headingPath()),
                escapeMarkers(document.chunkType()),
                escapeMarkers(document.content())
        );
    }

    /**
     * 把联网搜索结果格式化为不可信块。
     *
     * @param result 搜索结果
     * @return 格式化后的块文本
     */
    static String formatWeb(WebSearchResult result) {
        return """
                <<<BEGIN_UNTRUSTED_WEB_RESULT>>>
                title: %s
                url: %s
                snippet:
                %s
                <<<END_UNTRUSTED_WEB_RESULT>>>
                """.formatted(
                escapeMarkers(result.title()),
                escapeMarkers(result.url()),
                escapeMarkers(result.snippet())).strip();
    }

    /**
     * 转义内容里伪装成边界的字符串，防止片段内容提前闭合不可信区域。
     *
     * @param value 原始值
     * @return 转义后的值，null 转为空串
     */
    static String escapeMarkers(String value) {
        return value == null ? "" : value
                .replace(BEGIN_MARKER, "[ESCAPED_BEGIN_UNTRUSTED_")
                .replace(END_MARKER, "[ESCAPED_END_UNTRUSTED_");
    }

    /**
     * 截断单条历史消息，控制多轮上下文的 token 膨胀。
     *
     * @param content 消息内容
     * @return 截断后的内容
     */
    static String truncateHistoryMessage(String content) {
        if (content == null || content.length() <= HISTORY_MESSAGE_MAX_CHARS) {
            return content == null ? "" : content;
        }
        return content.substring(0, HISTORY_MESSAGE_MAX_CHARS) + "...";
    }

    /**
     * 按 token 预算拼装上下文：先整块放入，首块超预算时才截断，后续块放不下就跳过。
     *
     * @param sources   知识片段
     * @param maxTokens 上下文总预算
     * @return 以空行分隔的上下文文本
     */
    static String build(List<SourceDocument> sources, int maxTokens) {
        int usedTokens = 0;
        List<String> blocks = new ArrayList<>();
        for (SourceDocument source : sources) {
            String block = formatChunk(source);
            int tokens = estimateTokens(block);
            if (!blocks.isEmpty() && usedTokens + tokens > maxTokens) {
                continue;
            }
            if (blocks.isEmpty() && tokens > maxTokens) {
                block = truncateToTokenBudget(block, maxTokens);
                tokens = estimateTokens(block);
            }
            blocks.add(block);
            usedTokens += tokens;
        }
        return String.join("\n\n", blocks);
    }

    /**
     * 上下文策略：按内容去重、按文档限块、按 token 预算收敛。
     *
     * @param sources              候选片段
     * @param maxTokens            上下文总预算
     * @param maxChunksPerDocument 单文档最大块数
     * @return 最终进入提示词的片段
     */
    static List<SourceDocument> applyPolicy(
            List<SourceDocument> sources, int maxTokens, int maxChunksPerDocument) {
        LinkedHashMap<String, SourceDocument> byContent = new LinkedHashMap<>();
        Map<String, Integer> documentCounts = new LinkedHashMap<>();
        int usedTokens = 0;
        for (SourceDocument source : sources) {
            String normalized = source.content() == null ? ""
                    : source.content().replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || byContent.containsKey(normalized)) {
                continue;
            }
            String documentId = source.documentId() == null ? source.source() : source.documentId();
            if (documentCounts.getOrDefault(documentId, 0) >= maxChunksPerDocument) {
                continue;
            }
            int tokens = estimateTokens(formatChunk(source));
            if (!byContent.isEmpty() && usedTokens + tokens > maxTokens) {
                continue;
            }
            byContent.put(normalized, source);
            documentCounts.merge(documentId, 1, Integer::sum);
            usedTokens += tokens;
        }
        return List.copyOf(byContent.values());
    }
}
