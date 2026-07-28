package com.example.workbench.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record MessageResponse(Long id, String role, String content, JsonNode sources, JsonNode toolCalls, Instant createdAt) {
}
