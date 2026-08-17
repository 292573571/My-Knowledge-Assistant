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

    private AuthService service(AppUserRepository users, UserSessionRepository sessions,
                                EmailVerificationService verification) {
        return new AuthService(users, sessions, verification, 24);
    }

    @Test
    void registersUserWithHashedPasswordWithoutCreatingSession() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        EmailVerificationService verification = Mockito.mock(EmailVerificationService.class);
        when(users.findByAccount("alice@example.com")).thenReturn(Optional.empty());
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthService service = service(users, sessions, verification);

        RegisterResultResponse response = service.register(
                new RegisterRequest("alice@example.com", "123456", "correct-horse-battery"));

        Mockito.verify(verification).verify("alice@example.com", "123456");
        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        Mockito.verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getAccount()).isEqualTo("alice@example.com");
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(userCaptor.getValue().getUserName()).isEqualTo("alice");
        assertThat(userCaptor.getValue().getPublicId()).startsWith("usr_").hasSize(24);
        assertThat(userCaptor.getValue().getPasswordHash()).isNotEqualTo("correct-horse-battery");
        assertThat(response.message()).isEqualTo("注册成功，请登录");
        Mockito.verify(sessions, Mockito.never()).save(any(UserSession.class));
    }

    @Test
    void adminAccountCannotBeClaimedThroughPublicRegistration() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        EmailVerificationService verification = Mockito.mock(EmailVerificationService.class);
        AuthService service = service(users, sessions, verification);

        assertThatThrownBy(() -> service.register(new RegisterRequest("admin", "123456", "correct-horse-battery")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("系统保留账号");
    }

    @Test
    void rejectsInvalidLoginCredentials() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        EmailVerificationService verification = Mockito.mock(EmailVerificationService.class);
        when(users.findByAccount(anyString())).thenReturn(Optional.empty());
        AuthService service = service(users, sessions, verification);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice@example.com", "correct-horse-battery")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsNonEmailAccountForRegularLogin() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        EmailVerificationService verification = Mockito.mock(EmailVerificationService.class);
        when(users.findByAccount(anyString())).thenReturn(Optional.empty());
        AuthService service = service(users, sessions, verification);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "correct-horse-battery")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("账号必须是邮箱地址");
    }

    @Test
    void acceptsShortLoginPasswordSoCredentialsCanBeCheckedUniformly() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        EmailVerificationService verification = Mockito.mock(EmailVerificationService.class);
        when(users.findByAccount(anyString())).thenReturn(Optional.empty());
        AuthService service = service(users, sessions, verification);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("账号或密码错误");
    }

    @Test
    void changesPasswordAndRevokesOtherSessions() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        UserSessionRepository sessions = Mockito.mock(UserSessionRepository.class);
        EmailVerificationService verification = Mockito.mock(EmailVerificationService.class);
        AuthService service = service(users, sessions, verification);
        AppUser user = new AppUser("alice", "Alice", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("old-password"));

        service.changePassword(user, "current-token", new ChangePasswordRequest("old-password", "new-password", "new-password"));

        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("new-password", user.getPasswordHash())).isTrue();
        Mockito.verify(sessions).deleteOtherSessions(user.getId(), "current-token");
    }
}
