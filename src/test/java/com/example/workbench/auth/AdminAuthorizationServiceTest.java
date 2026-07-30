package com.example.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AdminAuthorizationServiceTest {

    @Test
    void recognizesDatabaseRolesAndBuiltInSuperAdmin() {
        AdminAuthorizationService service = new AdminAuthorizationService("admin, owner@example.com");

        assertThat(service.isAdmin(new AppUser("ADMIN", "Admin", "hash"))).isTrue();
        assertThat(service.isSuperAdmin(new AppUser("admin", "Admin", "hash"))).isTrue();
        AppUser databaseAdmin = new AppUser("database-admin", "Database Admin", "hash");
        databaseAdmin.changeSystemRole(SystemRole.ADMIN);
        assertThat(service.isAdmin(databaseAdmin)).isTrue();
        assertThat(service.isAdmin(new AppUser("owner@example.com", "Configured legacy admin", "hash"))).isFalse();
        assertThat(service.isAdmin(new AppUser("member", "Member", "hash"))).isFalse();
        assertThatThrownBy(() -> service.requireAdmin(new AppUser("member", "Member", "hash")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void adminAccountRemainsSuperAdminWhenNoEnvironmentAdministratorIsConfigured() {
        AdminAuthorizationService service = new AdminAuthorizationService("");

        assertThat(service.isAdmin(new AppUser("admin", "Admin", "hash"))).isTrue();
        assertThat(service.effectiveRole(new AppUser("admin", "Admin", "hash"))).isEqualTo(SystemRole.SUPER_ADMIN);
    }
}
