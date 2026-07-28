package com.example.workbench.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ConversationMemory {

    public static final String CONVERSATION_ID = "conversationId";

    private final Map<String, List<ChatMessage>> conversations = new ConcurrentHashMap<>();

    public List<ChatMessage> get(String conversationId) {
        return Collections.unmodifiableList(conversations.getOrDefault(conversationId, List.of()));
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
        conversations.computeIfAbsent(conversationId, key -> new ArrayList<>()).add(message);
    }
}
