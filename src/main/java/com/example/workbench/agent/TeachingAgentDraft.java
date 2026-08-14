package com.example.workbench.agent;

public record TeachingAgentDraft(String topic, String explanation, String checkQuestion) {
    public TeachingAgentDraft(String explanation, String checkQuestion) {
        this(null, explanation, checkQuestion);
    }
}
