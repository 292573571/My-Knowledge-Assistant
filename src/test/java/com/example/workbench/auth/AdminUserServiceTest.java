package com.example.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class AdminUserServiceTest {

    @Test
    void superAdminCanPromoteARegularUserToAdmin() {
        AppUserRepository repository = Mockito.mock(AppUserRepository.class);
        AdminUserService service = new AdminUserService(repository, new AdminAuthorizationService(""));
        AppUser actor = new AppUser("admin", "Admin", "hash");
        AppUser target = new AppUser("alice", "Alice", "hash");
        target.initializeProfile("usr_alice", "seed");
        when(repository.findByPublicId("usr_alice")).thenReturn(Optional.of(target));
        when(repository.save(target)).thenReturn(target);

        AdminUserResponse response = service.changeRole(actor, "usr_alice", new UpdateSystemRoleRequest(SystemRole.ADMIN));

        assertThat(response.systemRole()).isEqualTo(SystemRole.ADMIN);
        assertThat(target.getSystemRole()).isEqualTo(SystemRole.ADMIN);
        verify(repository).save(target);
    }

    @Test
    void regularAdminCanListButCannotChangeRoles() {
        AppUserRepository repository = Mockito.mock(AppUserRepository.class);
        AdminUserService service = new AdminUserService(repository, new AdminAuthorizationService(""));
        AppUser actor = new AppUser("operator", "Operator", "hash");
        actor.changeSystemRole(SystemRole.ADMIN);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(actor));

        assertThat(service.list(actor)).singleElement().satisfies(user ->
                assertThat(user.systemRole()).isEqualTo(SystemRole.ADMIN));
        assertThatThrownBy(() -> service.changeRole(actor, "usr_target", new UpdateSystemRoleRequest(SystemRole.USER)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void adminAccountCannotBeDowngradedAndNoSecondSuperAdminCanBeGranted() {
        AppUserRepository repository = Mockito.mock(AppUserRepository.class);
        AdminUserService service = new AdminUserService(repository, new AdminAuthorizationService(""));
        AppUser actor = new AppUser("admin", "Admin", "hash");
        actor.initializeProfile("usr_admin", "seed");
        when(repository.findByPublicId("usr_admin")).thenReturn(Optional.of(actor));

        assertThatThrownBy(() -> service.changeRole(actor, "usr_admin", new UpdateSystemRoleRequest(SystemRole.USER)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("admin 账号必须保持超级管理员角色");
        assertThatThrownBy(() -> service.changeRole(actor, "usr_other", new UpdateSystemRoleRequest(SystemRole.SUPER_ADMIN)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能通过用户管理接口授予超级管理员角色");
    }
}
