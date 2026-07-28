package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RagQualityGate {

    private final LocalChatClient chatClient;
    private final boolean enabled;

    public RagQualityGate(
            LocalChatClient chatClient,
            @Value("${workbench.rag.quality-gate.enabled:false}") boolean enabled
    ) {
        this.chatClient = chatClient;
        this.enabled = enabled;
    }

    public List<SourceDocument> relevantSources(String question, List<SourceDocument> candidates) {
        if (!enabled || candidates.isEmpty()) {
            return candidates;
        }

        StringBuilder prompt = new StringBuilder("""
                你是 RAG 召回质量评估器。判断哪些候选片段能直接为用户当前问题提供事实依据。
                仅保留直接相关、包含实质答案的片段；排除仅重复问题、无关主题、模型免责声明、拒答和无法支持答案的内容。
                只输出一行，格式必须是 KEEP: 编号列表，例如 KEEP: 1,3；没有可靠片段时输出 KEEP: none。

                当前问题：
                %s

                候选片段：
                """.formatted(question));
        for (int index = 0; index < candidates.size(); index++) {
            SourceDocument source = candidates.get(index);
            prompt.append("\n[").append(index + 1).append("] ")
                    .append(source.headingPath() == null ? source.fileName() : source.headingPath())
                    .append("\n")
                    .append(truncate(source.content(), 1_200))
                    .append("\n");
        }

        String verdict = chatClient.generate(prompt.toString());
        List<Integer> keptIndexes = parseKeptIndexes(verdict, candidates.size());
        if (keptIndexes == null) {
            // 评估服务不可用或返回格式异常时保留既有规则结果，不能让评估器阻断问答。
            return candidates;
        }

        List<SourceDocument> kept = new ArrayList<>();
        for (Integer index : keptIndexes) {
            kept.add(candidates.get(index));
        }
        return kept;
    }

    public boolean approvesAnswer(String question, String answer, List<SourceDocument> sources) {
        if (!enabled || answer == null || answer.isBlank() || sources.isEmpty()) {
            return true;
        }

        String context = sources.stream()
                .map(source -> "- " + truncate(source.content(), 1_200))
                .reduce("", (left, right) -> left + "\n" + right);
        String prompt = """
                你是 RAG 回答质量评估器。检查回答是否直接回答当前问题，且每个关键结论都能由给定资料支持。
                若回答偏题、把资料中的问题当答案、包含资料未支持的关键事实，输出 FAIL；否则输出 PASS。
                只能输出 PASS 或 FAIL。

                当前问题：
                %s

                回答：
                %s

                资料：
                %s
                """.formatted(question, answer, context);
        String verdict = chatClient.generate(prompt);
        if (verdict == null || verdict.isBlank()) {
            return true;
        }
        return !verdict.strip().toUpperCase().startsWith("FAIL");
    }

    private List<Integer> parseKeptIndexes(String verdict, int candidateCount) {
        if (verdict == null || verdict.isBlank()) {
            return null;
        }
        String normalized = verdict.strip().toUpperCase();
        if (!normalized.startsWith("KEEP:")) {
            return null;
        }
        String values = normalized.substring("KEEP:".length()).trim();
        if (values.equals("NONE")) {
            return List.of();
        }

        List<Integer> indexes = new ArrayList<>();
        for (String value : values.split(",")) {
            try {
                int index = Integer.parseInt(value.trim()) - 1;
                if (index < 0 || index >= candidateCount || indexes.contains(index)) {
                    return null;
                }
                indexes.add(index);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return indexes;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
