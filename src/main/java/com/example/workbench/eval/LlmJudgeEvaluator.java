package com.example.workbench.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class LlmJudgeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeEvaluator.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile("\\\"score\\\"\\s*[:：]\\s*([1-5])");
    private static final Pattern PASSED_PATTERN = Pattern.compile("\\\"passed\\\"\\s*[:：]\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public LlmJudgeEvaluator(ObjectProvider<ChatClient> chatClientProvider, ObjectMapper objectMapper) {
        this.chatClient = chatClientProvider == null ? null : chatClientProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public JudgeResult evaluate(
            EvalCase evalCase,
            String answer,
            List<String> actualSources,
            List<String> actualHeadingPaths
    ) {
        if (chatClient == null) {
            return unavailable("Judge ChatClient is not available");
        }

        try {
            String output = chatClient.prompt()
                    .user(prompt(evalCase, answer, actualSources, actualHeadingPaths))
                    .call()
                    .content();
            return parse(output);
        } catch (RuntimeException exception) {
            log.warn("LLM judge failed: {}", exception.getMessage());
            return unavailable("Judge call failed: " + exception.getMessage());
        }
    }

    JudgeResult parse(String output) {
        String cleaned = cleanJson(output);

        try {
            JudgeResult result = objectMapper.readValue(cleaned, JudgeResult.class);
            if (result.score() == null) {
                return unavailable("Judge output did not contain a score");
            }
            int score = Math.max(1, Math.min(5, result.score()));
            return new JudgeResult(score, score >= 3, true, result.reason());
        } catch (IOException exception) {
            Matcher scoreMatcher = SCORE_PATTERN.matcher(cleaned);
            Matcher passedMatcher = PASSED_PATTERN.matcher(cleaned);

            if (scoreMatcher.find() && passedMatcher.find()) {
                int score = Integer.parseInt(scoreMatcher.group(1));
                log.warn("LLM judge returned malformed JSON; recovered score and pass state");
                return new JudgeResult(score, score >= 3, true, "Judge output repaired after malformed JSON");
            }

            return unavailable("Judge output parse failed");
        }
    }

    private JudgeResult unavailable(String reason) {
        return new JudgeResult(null, false, false, reason);
    }

    private String cleanJson(String output) {
        if (output == null) {
            return "";
        }

        String cleaned = output.trim();

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length()).trim();
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length()).trim();
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length()).trim();
        }

        return cleaned;
    }

    private String prompt(
            EvalCase evalCase,
            String answer,
            List<String> actualSources,
            List<String> actualHeadingPaths
    ) {
        return """
                你是一个 RAG 问答质量评估器。

                你的任务是根据问题、回答、期望来源、实际来源、期望章节、实际章节，判断回答是否可靠。

                评分标准：
                1 分：回答完全错误，或与问题无关。
                2 分：回答只覆盖很少内容，或大部分没有依据。
                3 分：回答部分正确，但有明显缺失或表达不清。
                4 分：回答基本正确，基于来源，但有轻微不足。
                5 分：回答准确、完整、清晰，并且基于正确来源。

                特别规则：
                - 如果问题是 no_answer 类型，回答应该明确说明知识库没有相关信息或无法确认。
                - 如果 no_answer 问题中，回答编造了不存在的功能，应判为不通过。
                - 如果回答没有基于实际来源，应降低分数。
                - 如果回答虽然包含关键词，但没有真正回答问题，应降低分数。
                - 只输出 JSON，不要输出 Markdown，不要输出额外解释。

                输出格式：
                {
                  "score": 4,
                  "passed": true,
                  "reason": "回答基本正确，基于正确来源，但缺少一个细节。"
                }

                Question:
                %s

                Case Type:
                %s

                Expected Sources:
                %s

                Actual Sources:
                %s

                Expected Heading Paths:
                %s

                Actual Heading Paths:
                %s

                Answer:
                %s
                """.formatted(
                evalCase.question(),
                evalCase.type(),
                String.join(", ", nullSafe(evalCase.expectedSources())),
                String.join(", ", nullSafe(actualSources)),
                String.join(", ", nullSafe(evalCase.expectedHeadingPaths())),
                String.join(", ", nullSafe(actualHeadingPaths)),
                answer
        );
    }

    private List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
