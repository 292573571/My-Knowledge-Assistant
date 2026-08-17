package com.example.workbench.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final EmailService emailService;
    private final Duration ttl;
    private final Duration resendInterval;

    public EmailVerificationService(
            EmailService emailService,
            @Value("${app.mail.verification.code-ttl-minutes:10}") long ttlMinutes,
            @Value("${app.mail.verification.resend-seconds:60}") long resendSeconds
    ) {
        this.emailService = emailService;
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
        this.resendInterval = Duration.ofSeconds(Math.max(1, resendSeconds));
    }

    public void send(String email) {
        String normalized = normalize(email);
        Instant now = Instant.now();
        CodeEntry existing = codes.get(normalized);
        if (existing != null && now.isBefore(existing.lastSentAt().plus(resendInterval))) {
            long waitSeconds = Math.max(1, resendInterval.minus(Duration.between(existing.lastSentAt(), now)).toSeconds());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "发送过于频繁，请 " + waitSeconds + " 秒后重试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        codes.put(normalized, new CodeEntry(code, now.plus(ttl), now));
        emailService.sendVerificationCode(normalized, code);
    }

    public void verify(String email, String code) {
        String normalized = normalize(email);
        CodeEntry entry = codes.get(normalized);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码无效或已过期，请重新获取");
        }
        if (!entry.code().equals(code == null ? "" : code.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码错误");
        }
        codes.remove(normalized);
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private record CodeEntry(String code, Instant expiresAt, Instant lastSentAt) {
    }
}
