package com.example.workbench.conversation;

import java.time.Instant;

/**
 * 消息响应 DTO。
 *
 * <p>sources/toolCalls 用 {@code Object}（实际为 List/Map）而非 Jackson 2 的 {@code JsonNode}：
 * Spring Boot 4 起 MVC 消息转换器使用 Jackson 3，它不认识 Jackson 2 的 JsonNode，
 * 会按 bean 反射序列化成 {@code {"array":true,"bigDecimal":false,...}} 内部状态对象，
 * 前端拿到后无法当数组遍历，历史消息里 AI 回答整体渲染失败（ChatMessage 渲染抛错被
 * Vue 吞掉，表现为「AI 回答不显示」）。在 {@code ConversationService} 出口统一转成
 * plain List/Map，Jackson 3 即可正常序列化。</p>
 */
public record MessageResponse(Long id, String role, String content, Object sources, Object toolCalls, Instant createdAt) {
}
