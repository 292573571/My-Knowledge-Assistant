package com.example.workbench.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TeachingAgentOutputParser {

    private final ObjectMapper objectMapper;

    public TeachingAgentOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TeachingAgentDraft parse(String raw) {
        String fallback = raw == null || raw.isBlank() ? "模型未返回教学内容。" : raw.strip();
        if (raw == null || raw.isBlank()) return new TeachingAgentDraft(fallback, null);
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(raw));
            String explanation = text(root, "explanation");
            String checkQuestion = text(root, "checkQuestion");
            if (explanation == null || checkQuestion == null) return new TeachingAgentDraft(fallback, null);
            return new TeachingAgentDraft(explanation, checkQuestion);
        } catch (Exception ignored) {
            return new TeachingAgentDraft(fallback, null);
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) return null;
        return value.asText().strip();
    }

    private String stripCodeFence(String raw) {
        String value = raw.strip();
        if (!value.startsWith("```")) return value;
        int firstLineEnd = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) return value;
        return value.substring(firstLineEnd + 1, lastFence).strip();
    }
}
