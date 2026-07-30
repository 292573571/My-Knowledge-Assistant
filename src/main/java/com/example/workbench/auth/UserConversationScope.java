package com.example.workbench.auth;

public final class UserConversationScope {

    private UserConversationScope() {
    }

    public static String id(AppUser user, String conversationId) {
        return "user-" + ownerId(user) + ":" + conversationId;
    }

    public static String ownerId(AppUser user) {
        return user.getId() == null ? user.getAccount() : user.getId().toString();
    }
}
