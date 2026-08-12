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
        TeachingReadOnlyService.KnowledgeSearchResult result = invoke("searchKnowledge",
                () -> readOnlyService.search(context, query, limit));
        result.sources().forEach(source -> sources.putIfAbsent(sourceKey(source), source));
        return result;
    }

    @Tool(description = "查询当前登录用户最近的学习记录摘要，用于避免重复教学；limit 范围为 1 到 20")
    public TeachingReadOnlyService.LearningHistorySummary getRecentLearningRecords(
            @ToolParam(description = "返回记录数量，范围为 1 到 20") int limit) {
        return invoke("getRecentLearningRecords", () -> readOnlyService.recentLearningRecords(context, limit));
    }

    public synchronized List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public synchronized List<RagSource> sources() {
        return List.copyOf(sources.values());
    }

    private synchronized <T> T invoke(String toolName, Supplier<T> operation) {
        if (++callCount > MAX_TOOL_CALLS) {
            invocations.add(new Invocation(toolName, "REJECTED"));
            throw new IllegalStateException("教学 Agent 已达到只读工具调用上限");
        }
        try {
            T result = operation.get();
            invocations.add(new Invocation(toolName, "SUCCEEDED"));
            return result;
        } catch (RuntimeException exception) {
            invocations.add(new Invocation(toolName, "FAILED"));
            throw exception;
        }
    }

    private String sourceKey(RagSource source) {
        return source.file() + "#" + (source.pageNumber() == null ? "chunk-" + source.chunkIndex() : "page-" + source.pageNumber());
    }

    public record Invocation(String toolName, String status) {
    }
}
