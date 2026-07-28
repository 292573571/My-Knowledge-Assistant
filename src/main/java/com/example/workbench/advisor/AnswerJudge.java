package com.example.workbench.advisor;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AnswerJudge {

    public boolean isGrounded(String answer, List<String> contexts) {
        if (answer == null || answer.isBlank() || contexts.isEmpty()) {
            return false;
        }

        String normalizedAnswer = answer.toLowerCase();
        return contexts.stream()
                .map(String::toLowerCase)
                .anyMatch(context -> hasSharedSignal(normalizedAnswer, context));
    }

    private boolean hasSharedSignal(String answer, String context) {
        return context.lines()
                .map(String::trim)
                .filter(line -> line.length() >= 8)
                .anyMatch(line -> answer.contains(line.substring(0, Math.min(12, line.length())).toLowerCase()))
                || (answer.contains("mcp") && context.contains("mcp"));
    }
}
