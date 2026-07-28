package com.example.workbench.auth;

public final class UserConversationScope {

    private UserConversationScope() {
    }

    public static String id(AppUser user, String conversationId) {
        return "user-" + user.getId() + ":" + conversationId;
    }
}
