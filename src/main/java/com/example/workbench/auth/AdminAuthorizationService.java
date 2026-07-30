package com.example.workbench.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAuthorizationService {

    public AdminAuthorizationService(@Value("${app.security.admin-accounts:}") String adminAccounts) {
        // ADMIN_ACCOUNTS 仅由 SystemRoleBootstrap 在启动时迁移到数据库角色。
    }

    public boolean isAdmin(AppUser user) {
        return user != null && effectiveRole(user).canAdministerSystem();
    }

    public boolean isSuperAdmin(AppUser user) {
        return user != null && effectiveRole(user) == SystemRole.SUPER_ADMIN;
    }

    public SystemRole effectiveRole(AppUser user) {
        if (user == null) {
            return SystemRole.USER;
        }
        String account = user.getAccount().trim().toLowerCase();
        if ("admin".equals(account)) {
            return SystemRole.SUPER_ADMIN;
        }
        if (user.getSystemRole().canAdministerSystem()) {
            return user.getSystemRole();
        }
        return SystemRole.USER;
    }

    public SystemRole effectiveRole(String account, SystemRole persistedRole) {
        if ("admin".equalsIgnoreCase(account)) {
            return SystemRole.SUPER_ADMIN;
        }
        if (persistedRole != null && persistedRole.canAdministerSystem()) {
            return persistedRole;
        }
        return SystemRole.USER;
    }

    public void requireAdmin(AppUser user) {
        if (!isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该操作仅限系统管理员");
        }
    }

    public void requireSuperAdmin(AppUser user) {
        if (!isSuperAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该操作仅限超级管理员");
        }
    }
}
