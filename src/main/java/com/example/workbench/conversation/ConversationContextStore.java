package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import com.example.workbench.memory.ChatMessage;
import java.util.List;

public interface ConversationContextStore {

    List<ChatMessage> recent(AppUser user, String workspaceId, String clientConversationId, int maxRounds);
}
