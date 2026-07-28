package com.example.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuthServiceTest {

    @Test
    void registersUserWithHashedPasswordAndCreatesSession() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        when(users.findByAccount("alice")).thenReturn(Optional.empty());
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.save(any(UserSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthService service = new AuthService(users, sessions, 24);

        AuthResponse response = service.register(new RegisterRequest("Alice", "correct-horse-battery"));

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        Mockito.verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getAccount()).isEqualTo("alice");
        assertThat(userCaptor.getValue().getUserName()).isEqualTo("alice");
        assertThat(userCaptor.getValue().getPublicId()).startsWith("usr_").hasSize(24);
        assertThat(userCaptor.getValue().getAvatarSeed()).isNotBlank();
        assertThat(userCaptor.getValue().getPasswordHash()).isNotEqualTo("correct-horse-battery");
        assertThat(response.account()).isEqualTo("alice");
        assertThat(response.userName()).isEqualTo("alice");
        assertThat(response.publicId()).isEqualTo(userCaptor.getValue().getPublicId());
        assertThat(response.avatarUrl()).isEqualTo("/api/auth/avatar");
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void rejectsInvalidLoginCredentials() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        when(users.findByAccount(anyString())).thenReturn(Optional.empty());
        AuthService service = new AuthService(users, sessions, 24);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "correct-horse-battery")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void acceptsShortLoginPasswordSoCredentialsCanBeCheckedUniformly() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        when(users.findByAccount(anyString())).thenReturn(Optional.empty());
        AuthService service = new AuthService(users, sessions, 24);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("账号或密码错误");
    }

    @Test
    void changesPasswordAndRevokesOtherSessions() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        AuthService service = new AuthService(users, sessions, 24);
        AppUser user = new AppUser("alice", "Alice", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("old-password"));

        service.changePassword(user, "current-token", new ChangePasswordRequest("old-password", "new-password", "new-password"));

        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("new-password", user.getPasswordHash())).isTrue();
        Mockito.verify(sessions).deleteOtherSessions(user.getId(), "current-token");
    }
}
