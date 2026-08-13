package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import com.example.workbench.memory.ChatMessage;
import com.example.workbench.memory.ConversationMemory;
import java.util.List;

public final class InMemoryConversationContextStore implements ConversationContextStore {

    private final ConversationMemory memory;

    public InMemoryConversationContextStore(ConversationMemory memory) {
        this.memory = memory;
    }

    @Override
    public List<ChatMessage> recent(AppUser user, String workspaceId, String clientConversationId, int maxRounds) {
        return memory.recent(clientConversationId, maxRounds);
    }
}
