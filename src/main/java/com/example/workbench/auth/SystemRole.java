package com.example.workbench.auth;

public enum SystemRole {
    USER,
    ADMIN,
    SUPER_ADMIN;

    public boolean canAdministerSystem() {
        return this == ADMIN || this == SUPER_ADMIN;
    }
}
