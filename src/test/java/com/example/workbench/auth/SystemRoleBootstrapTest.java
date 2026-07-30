package com.example.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.DefaultApplicationArguments;

class SystemRoleBootstrapTest {

    @Test
    void persistsAdminAsSuperAdminAndMigratesLegacyConfiguredAccounts() throws Exception {
        AppUserRepository repository = Mockito.mock(AppUserRepository.class);
        AppUser admin = new AppUser("admin", "Admin", "hash");
        AppUser legacyAdmin = new AppUser("owner@example.com", "Owner", "hash");
        when(repository.findByAccount("admin")).thenReturn(Optional.of(admin));
        when(repository.findByAccount("owner@example.com")).thenReturn(Optional.of(legacyAdmin));
        SystemRoleBootstrap bootstrap = new SystemRoleBootstrap(repository, "owner@example.com");

        bootstrap.run(new DefaultApplicationArguments());

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository, Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AppUser::getSystemRole)
                .containsExactlyInAnyOrder(SystemRole.SUPER_ADMIN, SystemRole.ADMIN);
    }
}
