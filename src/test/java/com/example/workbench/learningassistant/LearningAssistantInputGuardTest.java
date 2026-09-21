package com.example.workbench.learningassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LearningAssistantInputGuardTest {

    private final LearningAssistantService service =
            new LearningAssistantService(null, null, null, null, null, null, null);

    @Test
    void shortMessageKeptAsIs() {
        String input = "帮我看看这段日志";
        assertEquals(input, service.guardModelInput(input));
    }

    @Test
    void messageAtLimitKeptAsIs() {
        String input = "字".repeat(24_000);
        assertEquals(input, service.guardModelInput(input));
    }

    @Test
    void longMessageTruncatedKeepingHeadAndTail() {
        String input = "A".repeat(30_000);

        String guarded = service.guardModelInput(input);

        assertTrue(guarded.length() < input.length(), "超长输入必须被截断");
        assertTrue(guarded.startsWith("A".repeat(16_000)));
        assertTrue(guarded.endsWith("A".repeat(8_000)));
        assertTrue(guarded.contains("中间省略"), "截断处要有显式标记: " + guarded.length());
    }
}
