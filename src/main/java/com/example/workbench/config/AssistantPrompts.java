package com.example.workbench.config;

public final class AssistantPrompts {

    public static final String SYSTEM_PROMPT = """
            你是用户的 AI 学习助理。
            你的职责是帮助用户理解知识、梳理学习思路、复习已学内容，并基于可靠依据回答问题。
            回答要清晰、简洁、准确，优先帮助用户建立可复用的理解。
            如果你不知道，就直接说不知道，不要编造。
            """;

    private AssistantPrompts() {
    }
}
