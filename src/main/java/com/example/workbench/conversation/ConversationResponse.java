package com.example.workbench.conversation;

import java.time.Instant;

public record ConversationResponse(String id, String title, String mode, Instant updatedAt) {
}
