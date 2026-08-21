package com.example.workbench.learning;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 学习记录的 Markdown 格式化与答案清洗纯函数集合。
 *
 * 不持有任何共享可变状态，仅基于传入的字符串/参数计算结果，
 * 因此可在摄入锁临界区外安全调用，也便于独立测试。
 */
public final class LearningRecordFormatter {

    private static final List<String> UNRELIABLE_ANSWER_MARKERS = List.of(
            "我在当前知识库中没有找到足够信息和依据来回答这个问题",
            "当前知识库没有足够信息回答该问题",
            "当前知识库没有包含任何信息",
            "当前知识库中没有包含任何信息",
            "当前知识库没有任何信息",
            "当前知识库为空",
            "当前知识库没有相关资料，且模型回退调用未能成功",
            "请求失败，请稍后重试",
            "暂时无法回答",
            "无法回答这个问题"
    );

    public String withoutGeneratedTitle(String date, String content) {
        String formalHeader = "# " + date + " 正式笔记";
        String learningHeader = "# " + date + " 学习记录";
        String normalized = content.stripLeading();

        while (normalized.startsWith(formalHeader) || normalized.startsWith(learningHeader)) {
            String header = normalized.startsWith(formalHeader) ? formalHeader : learningHeader;
            normalized = normalized.substring(header.length()).stripLeading();
        }

        return normalized;
    }

    public String formatEntry(String workspaceId, String question, String answer) {
        return "\n## 问题\n\n"
                + (workspaceId == null || workspaceId.isBlank() ? "" : "- 知识空间：" + singleLine(workspaceId) + "\n\n")
                + question.strip()
                + "\n\n## 回答\n\n"
                + withoutReferences(answer)
                + "\n";
    }

    public String formatTeachingPractice(String workspaceId, String checkId, String practiceId, String topic,
                                        String question, String answer, int score, int maxScore,
                                        boolean passed, String feedback) {
        return "\n## 教学实践\n\n"
                + "- 主题：" + singleLine(topic) + "\n"
                + (workspaceId == null || workspaceId.isBlank() ? "" : "- 知识空间：" + singleLine(workspaceId) + "\n")
                + "- 检查标识：" + singleLine(checkId) + "\n"
                + "- 实践标识：" + singleLine(practiceId) + "\n"
                + "- 实践问题：" + singleLine(question) + "\n"
                + "- 我的回答：" + singleLine(answer) + "\n"
                + "- 得分：" + score + "/" + maxScore + "\n"
                + "- 结果：" + (passed ? "通过" : "需要复习") + "\n"
                + "- 反馈：" + singleLine(feedback) + "\n";
    }

    public String formatTeachingCheck(String workspaceId, String attemptId, String topic, String question, String answer,
                                     int score, int maxScore, boolean passed, String feedback,
                                     String weakPoint, String reviewExplanation, String reviewSuggestion) {
        StringBuilder entry = new StringBuilder("\n## 教学检查\n\n")
                .append("- 主题：").append(singleLine(topic)).append('\n')
                .append(workspaceId == null || workspaceId.isBlank() ? "" : "- 知识空间：" + singleLine(workspaceId) + "\n")
                .append("- 检查问题：").append(singleLine(question)).append('\n')
                .append("- 我的回答：").append(singleLine(answer)).append('\n')
                .append("- 得分：").append(score).append('/').append(maxScore).append('\n')
                .append("- 结果：").append(passed ? "通过" : "需要复习").append('\n')
                .append("- 反馈：").append(singleLine(feedback)).append('\n')
                .append("- attemptId：").append(singleLine(attemptId)).append('\n');
        if (!passed && weakPoint != null) {
            entry.append("\n### 针对性复习\n\n")
                    .append("- 薄弱点：").append(singleLine(weakPoint)).append('\n')
                    .append("- 关键解释：").append(singleLine(reviewExplanation)).append('\n')
                    .append("- 复习建议：").append(singleLine(reviewSuggestion)).append('\n');
        }
        return entry.toString();
    }

    public String singleLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s*\\R\\s*", " ");
    }

    public String withoutReferences(String answer) {
        return answer.strip()
                .replaceFirst("(?ms)\\n+参考来源[：:]\\s*\\n.*$", "")
                .replaceAll("(?m)^\\s*以上回答基于通用大模型知识，不是当前知识库内容。\\s*$\\R?", "")
                .strip();
    }

    public boolean isRecordableAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }

        String cleaned = withoutReferences(answer);
        if (UNRELIABLE_ANSWER_MARKERS.stream().anyMatch(cleaned::contains)) {
            return false;
        }

        String normalized = cleaned.strip();
        if (normalized.startsWith("我不知道")
                || normalized.startsWith("抱歉，我无法")
                || normalized.startsWith("抱歉，无法")) {
            return false;
        }

        String meaningful = normalized
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
        return meaningful.length() >= 6;
    }

    public String normalizeQuestion(String question) {
        return question.strip().replaceAll("\\s+", " ");
    }

    public String safeWorkspaceDirectory(String workspaceId) {
        return "workspace-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(workspaceId.getBytes(StandardCharsets.UTF_8));
    }
}
