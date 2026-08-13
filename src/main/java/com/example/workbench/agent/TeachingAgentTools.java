package com.example.workbench.agent;

import com.example.workbench.rag.RagSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 每个教学请求独占的只读工具集合，身份和空间上下文不会暴露给模型。 */
public class TeachingAgentTools {

    public static final int MAX_TOOL_CALLS = 5;
    private static final int MAX_QUERY_LENGTH = 1000;

    private final TeachingReadOnlyService readOnlyService;
    private final TeachingAgentContext context;
    private final List<Invocation> invocations = new ArrayList<>();
    private final Map<String, RagSource> sources = new LinkedHashMap<>();
    private int callCount;

    public TeachingAgentTools(TeachingReadOnlyService readOnlyService, TeachingAgentContext context) {
        this.readOnlyService = readOnlyService;
        this.context = context;
    }

    @Tool(description = "检索当前授权知识空间中与学习主题或用户问题直接相关的资料。回答知识问题前必须调用；limit 范围为 1 到 10")
    public TeachingReadOnlyService.KnowledgeSearchResult searchKnowledge(
            @ToolParam(description = "学习主题或具体问题") String query,
            @ToolParam(description = "返回资料数量，范围为 1 到 10") int limit) {
        String safeQuery = validateQuery(query);
        int safeLimit = boundedLimit(limit, 10);
        TeachingReadOnlyService.KnowledgeSearchResult result = invoke("searchKnowledge",
                () -> readOnlyService.search(context, safeQuery, safeLimit));
        result.sources().forEach(source -> sources.putIfAbsent(sourceKey(source), source));
        return result;
    }

    @Tool(description = "查询当前登录用户最近的学习记录摘要，用于避免重复教学；limit 范围为 1 到 20")
    public TeachingReadOnlyService.LearningHistorySummary getRecentLearningRecords(
            @ToolParam(description = "返回记录数量，范围为 1 到 20") int limit) {
        int safeLimit = boundedLimit(limit, 20);
        return invoke("getRecentLearningRecords", () -> readOnlyService.recentLearningRecords(context, safeLimit));
    }

    public synchronized List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public synchronized List<RagSource> sources() {
        return List.copyOf(sources.values());
    }

    private synchronized <T> T invoke(String toolName, Supplier<T> operation) {
        if (++callCount > MAX_TOOL_CALLS) {
            invocations.add(new Invocation(toolName, "REJECTED", "已达到只读工具调用上限"));
            throw new IllegalStateException("教学 Agent 已达到只读工具调用上限");
        }
        try {
            T result = operation.get();
            invocations.add(new Invocation(toolName, "SUCCEEDED", null));
            return result;
        } catch (RuntimeException exception) {
            invocations.add(new Invocation(toolName, "FAILED", "工具暂时不可用，请稍后重试"));
            throw exception;
        }
    }

    private String sourceKey(RagSource source) {
        return source.file() + "#" + (source.pageNumber() == null ? "chunk-" + source.chunkIndex() : "page-" + source.pageNumber());
    }

    private String validateQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        String normalized = query.strip();
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("检索问题不能超过 " + MAX_QUERY_LENGTH + " 个字符");
        }
        if (normalized.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
            throw new IllegalArgumentException("检索问题包含不支持的控制字符");
        }
        return normalized;
    }

    private int boundedLimit(int limit, int max) {
        return Math.max(1, Math.min(max, limit));
    }

    public record Invocation(String toolName, String status, String detail) {
        public Invocation(String toolName, String status) {
            this(toolName, status, null);
        }
    }
}
