package com.example.workbench.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConversationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String id,
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Pattern(regexp = "rag|chat") String mode
) {
}
