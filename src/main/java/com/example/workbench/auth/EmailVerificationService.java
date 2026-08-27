package com.example.workbench.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.example.workbench.config.RateLimiter;

@Component
public class EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder CODE_ENCODER = new BCryptPasswordEncoder();

    private final EmailVerificationCodeRepository repository;
    private final EmailVerificationSendRepository sendRepository;
    private final EmailService emailService;
    private final Duration ttl;
    private final Duration resendInterval;
    private final Duration ipWindow;
    private final int maxAttempts;
    private final int maxIpSends;
    private final RateLimiter rateLimiter;
    private final int distributedIpSends;
    private final int distributedEmailSends;

    public EmailVerificationService(
            EmailService emailService,
            EmailVerificationCodeRepository repository,
            EmailVerificationSendRepository sendRepository,
            long ttlMinutes,
            long resendSeconds,
            long ipWindowMinutes,
            int maxAttempts,
            int maxIpSends
    ) {
        this(emailService, repository, sendRepository, ttlMinutes, resendSeconds, ipWindowMinutes,
                maxAttempts, maxIpSends, null, maxIpSends, 5);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EmailVerificationService(
            EmailService emailService,
            EmailVerificationCodeRepository repository,
            EmailVerificationSendRepository sendRepository,
            @Value("${app.mail.verification.code-ttl-minutes:10}") long ttlMinutes,
            @Value("${app.mail.verification.resend-seconds:60}") long resendSeconds,
            @Value("${app.mail.verification.ip-window-minutes:60}") long ipWindowMinutes,
            @Value("${app.mail.verification.max-attempts:5}") int maxAttempts,
            @Value("${app.mail.verification.max-ip-sends:10}") int maxIpSends,
            RateLimiter rateLimiter,
            @Value("${app.rate-limit.verification-per-ip:10}") int distributedIpSends,
            @Value("${app.rate-limit.verification-per-email:5}") int distributedEmailSends
    ) {
        this.repository = repository;
        this.sendRepository = sendRepository;
        this.emailService = emailService;
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
        this.resendInterval = Duration.ofSeconds(Math.max(1, resendSeconds));
        this.ipWindow = Duration.ofMinutes(Math.max(1, ipWindowMinutes));
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxIpSends = Math.max(1, maxIpSends);
        this.rateLimiter = rateLimiter;
        this.distributedIpSends = Math.max(1, distributedIpSends);
        this.distributedEmailSends = Math.max(1, distributedEmailSends);
    }

    @Transactional
    public void send(String email, String ipAddress) {
        String normalized = normalize(email);
        Instant now = Instant.now();
        String normalizedIp = normalizeIp(ipAddress);
        if (rateLimiter != null && (!rateLimiter.tryAcquire("verification-ip", normalizedIp, distributedIpSends, ipWindow)
                || !rateLimiter.tryAcquire("verification-email", normalized, distributedEmailSends, ipWindow))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "当前请求过于频繁，请稍后重试");
        }
        repository.deleteByExpiresAtBefore(now);
        sendRepository.deleteBySentAtBefore(now.minus(ipWindow));
        EmailVerificationCode existing = repository.findByEmail(normalized).orElse(null);
        if (existing != null && now.isBefore(existing.getLastSentAt().plus(resendInterval))) {
            long waitSeconds = Math.max(1, resendInterval.minus(Duration.between(existing.getLastSentAt(), now)).toSeconds());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "发送过于频繁，请 " + waitSeconds + " 秒后重试");
        }
        if (sendRepository.countRecentByIp(normalizedIp, now.minus(ipWindow)) >= maxIpSends) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "当前请求过于频繁，请稍后重试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        if (existing == null) {
            repository.save(new EmailVerificationCode(normalized, hash(code), now.plus(ttl), now, normalizedIp));
        } else {
            existing.renew(hash(code), now.plus(ttl), now, normalizedIp);
        }
        sendRepository.save(new EmailVerificationSend(normalized, normalizedIp, now));
        emailService.sendVerificationCode(normalized, code);
    }

    @Transactional
    public void verify(String email, String code) {
        String normalized = normalize(email);
        EmailVerificationCode entry = repository.findByEmail(normalized).orElse(null);
        if (entry == null || entry.getExpiresAt().isBefore(Instant.now())) {
            repository.deleteByEmail(normalized);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码无效或已过期，请重新获取");
        }
        if (entry.getFailedAttempts() >= maxAttempts || !CODE_ENCODER.matches(
                code == null ? "" : code.trim(), entry.getCodeHash())) {
            entry.recordFailure();
            if (entry.getFailedAttempts() >= maxAttempts) repository.deleteByEmail(normalized);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码错误");
        }
        repository.deleteByEmail(normalized);
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String normalizeIp(String ipAddress) {
        return ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress.strip().substring(0, Math.min(64, ipAddress.strip().length()));
    }

    private String hash(String value) {
        return CODE_ENCODER.encode(value);
    }
}
