package com.example.workbench.mcp;

import com.example.workbench.auth.AppUser;

/** MCP 请求上下文：SYNC 模式下工具与 Filter 同线程，借此传递当前认证用户。 */
public class McpRequestContext {

    private static final ThreadLocal<AppUser> CURRENT_USER = new ThreadLocal<>();

    private McpRequestContext() {
    }

    public static void set(AppUser user) {
        if (user == null) {
            clear();
            return;
        }
        CURRENT_USER.set(user);
    }

    public static AppUser currentUser() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
