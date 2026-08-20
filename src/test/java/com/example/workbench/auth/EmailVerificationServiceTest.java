package com.example.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class EmailVerificationServiceTest {

    @Test
    void persistsOnlyAHashAndVerifiesTheGeneratedCode() {
        EmailService emailService = Mockito.mock(EmailService.class);
        EmailVerificationCodeRepository codes = Mockito.mock(EmailVerificationCodeRepository.class);
        EmailVerificationSendRepository sends = Mockito.mock(EmailVerificationSendRepository.class);
        when(codes.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(sends.countRecentByIp(any(), any())).thenReturn(0L);
        EmailVerificationService service = new EmailVerificationService(emailService, codes, sends, 10, 60, 60, 5, 10);

        service.send(" Alice@Example.com ", "127.0.0.1");

        ArgumentCaptor<EmailVerificationCode> codeCaptor = ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(codes).save(codeCaptor.capture());
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationCode(Mockito.eq("alice@example.com"), valueCaptor.capture());
        String generatedCode = valueCaptor.getValue();
        EmailVerificationCode stored = codeCaptor.getValue();
        assertThat(generatedCode).matches("\\d{6}");
        assertThat(stored.getCodeHash()).isNotEqualTo(generatedCode).hasSize(60);

        when(codes.findByEmail("alice@example.com")).thenReturn(Optional.of(stored));
        service.verify("ALICE@example.com", generatedCode);

        verify(codes).deleteByEmail("alice@example.com");
    }

    @Test
    void rejectsRapidResendsAndIpLimit() {
        EmailService emailService = Mockito.mock(EmailService.class);
        EmailVerificationCodeRepository codes = Mockito.mock(EmailVerificationCodeRepository.class);
        EmailVerificationSendRepository sends = Mockito.mock(EmailVerificationSendRepository.class);
        EmailVerificationCode existing = new EmailVerificationCode(
                "alice@example.com", "hash", Instant.now().plusSeconds(600), Instant.now(), "127.0.0.1");
        when(codes.findByEmail("alice@example.com")).thenReturn(Optional.of(existing));
        when(sends.countRecentByIp(any(), any())).thenReturn(10L);
        EmailVerificationService service = new EmailVerificationService(emailService, codes, sends, 10, 60, 60, 5, 10);

        assertThatThrownBy(() -> service.send("alice@example.com", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("发送过于频繁");
        verify(emailService, never()).sendVerificationCode(any(), any());

        when(codes.findByEmail("alice@example.com")).thenReturn(Optional.of(new EmailVerificationCode(
                "alice@example.com", "hash", Instant.now().plusSeconds(600),
                Instant.now().minusSeconds(120), "127.0.0.1")));
        assertThatThrownBy(() -> service.send("alice@example.com", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("当前请求过于频繁");
    }

    @Test
    void deletesCodeAfterMaximumFailedAttempts() {
        EmailService emailService = Mockito.mock(EmailService.class);
        EmailVerificationCodeRepository codes = Mockito.mock(EmailVerificationCodeRepository.class);
        EmailVerificationSendRepository sends = Mockito.mock(EmailVerificationSendRepository.class);
        EmailVerificationCode existing = new EmailVerificationCode(
                "alice@example.com", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("123456"),
                Instant.now().plusSeconds(600), Instant.now(), "127.0.0.1");
        when(codes.findByEmail("alice@example.com")).thenReturn(Optional.of(existing));
        EmailVerificationService service = new EmailVerificationService(emailService, codes, sends, 10, 60, 60, 1, 10);

        assertThatThrownBy(() -> service.verify("alice@example.com", "000000"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("验证码错误");

        verify(codes).deleteByEmail("alice@example.com");
    }
}
