package com.example.workbench.workbench;

import java.util.List;

public record WorkbenchChatResponse(
        String messageId,
        String answer,
        List<?> sources,
        List<?> toolCalls
) {
}
