package com.example.workbench.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ConversationMemory {

    public static final String CONVERSATION_ID = "conversationId";

    private final Map<String, List<ChatMessage>> conversations = new ConcurrentHashMap<>();

    public List<ChatMessage> get(String conversationId) {
        List<ChatMessage> messages = conversations.getOrDefault(conversationId, List.of());
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    /**
     * 返回最近若干个完整对话轮次的消息。
     *
     * @param conversationId 会话标识
     * @param maxRounds 最大轮次数
     * @return 按时间正序排列的最近消息副本
     */
    public List<ChatMessage> recent(String conversationId, int maxRounds) {
        List<ChatMessage> messages = conversations.getOrDefault(conversationId, List.of());
        synchronized (messages) {
            int maxMessages = Math.max(0, maxRounds) * 2;
            int fromIndex = Math.max(0, messages.size() - maxMessages);
            return List.copyOf(messages.subList(fromIndex, messages.size()));
        }
    }

    public void addUserMessage(String conversationId, String message) {
        add(conversationId, new ChatMessage("user", message));
    }

    public void addAssistantMessage(String conversationId, String message) {
        add(conversationId, new ChatMessage("assistant", message));
    }

    public void remove(String conversationId) {
        conversations.remove(conversationId);
    }

    private void add(String conversationId, ChatMessage message) {
        List<ChatMessage> messages = conversations.computeIfAbsent(conversationId, key -> new ArrayList<>());
        synchronized (messages) {
            messages.add(message);
        }
    }
}
