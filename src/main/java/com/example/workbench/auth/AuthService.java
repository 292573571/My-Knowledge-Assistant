package com.example.workbench.auth;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.workbench.config.RateLimiter;

@Service
public class AuthService {

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final AppUserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final EmailVerificationService emailVerificationService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Duration sessionDuration;
    private final RateLimiter rateLimiter;
    private final int loginPerIp;
    private final int loginPerAccount;

    public AuthService(
            AppUserRepository userRepository,
            UserSessionRepository sessionRepository,
            EmailVerificationService emailVerificationService,
            @Value("${app.auth.session-hours:24}") long sessionHours
    ) {
        this(userRepository, sessionRepository, emailVerificationService, sessionHours, null, 10, 10);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(
            AppUserRepository userRepository,
            UserSessionRepository sessionRepository,
            EmailVerificationService emailVerificationService,
            @Value("${app.auth.session-hours:24}") long sessionHours,
            RateLimiter rateLimiter,
            @Value("${app.rate-limit.login-per-ip:10}") int loginPerIp,
            @Value("${app.rate-limit.login-per-account:10}") int loginPerAccount
    ) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.emailVerificationService = emailVerificationService;
        this.sessionDuration = Duration.ofHours(Math.max(1, sessionHours));
        this.rateLimiter = rateLimiter;
        this.loginPerIp = Math.max(1, loginPerIp);
        this.loginPerAccount = Math.max(1, loginPerAccount);
    }

    @Transactional
    public RegisterResultResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if ("admin".equals(email)) {
            throw new IllegalArgumentException("admin 为系统保留账号，不能通过注册接口创建");
        }
        if (userRepository.findByAccount(email).isPresent()) {
            log.warn("User registration rejected reason=account_already_registered");
            throw new IllegalArgumentException("该邮箱已注册");
        }
        emailVerificationService.verify(email, request.code());

        String userName = emailPrefix(email);
        AppUser user = new AppUser(email, email, userName, passwordEncoder.encode(request.password()));
        initializeProfile(user);
        userRepository.save(user);
        log.info("User registered userId={}", user.getId());
        return new RegisterResultResponse("注册成功，请登录");
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        return login(request, "unknown");
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        String account = normalizeAccount(request.account());
        if (rateLimiter != null && (!rateLimiter.tryAcquire("login-ip", ipAddress, loginPerIp, Duration.ofMinutes(1))
                || !rateLimiter.tryAcquire("login-account", account, loginPerAccount, Duration.ofMinutes(1)))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后重试");
        }
        if (!"admin".equals(account) && !isEmailFormat(account)) {
            AppUser existing = userRepository.findByAccount(account).orElse(null);
            if (existing == null || !existing.getSystemRole().canAdministerSystem()) {
                log.warn("User login rejected reason=account_not_email");
                throw new InvalidCredentialsException("账号必须是邮箱地址");
            }
        }
        AppUser user = userRepository.findByAccount(account)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> {
                    log.warn("User login rejected reason=invalid_credentials");
                    return new InvalidCredentialsException("账号或密码错误");
                });
        log.info("User logged in userId={}", user.getId());
        return createSession(user);
    }

    @Transactional
    public AppUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidCredentialsException("authentication is required");
        }

        String hash = tokenHash(token);
        UserSession session = sessionRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidCredentialsException("invalid or expired session"));
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidCredentialsException("invalid or expired session");
        }
        AppUser user = session.getUser();
        user.getAccount();
        initializeProfile(user);
        return user;
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessionRepository.deleteByTokenHash(tokenHash(token));
            log.info("User logged out");
        }
    }

    @Transactional
    public void changePassword(AppUser user, String currentToken, ChangePasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次输入的新密码不一致");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("Password change rejected reason=invalid_current_password userId={}", user.getId());
            throw new InvalidCredentialsException("当前密码错误");
        }

        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        sessionRepository.deleteOtherSessions(user.getId(), tokenHash(currentToken));
        log.info("Password changed userId={} otherSessionsRevoked=true", user.getId());
    }

    private AuthResponse createSession(AppUser user) {
        String token = newToken();
        sessionRepository.save(new UserSession(user, tokenHash(token), Instant.now().plus(sessionDuration)));
        log.info("User session created userId={} expiresInHours={}", user.getId(), sessionDuration.toHours());
        initializeProfile(user);
        return new AuthResponse(token, user.getAccount(), user.getEmail(), user.getPhone(), user.getUserName(), user.getPublicId(), "/api/auth/avatar",
                user.getCreatedAt(), user.getSystemRole());
    }

    private String normalizeAccount(String account) {
        return account.trim().toLowerCase();
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String emailPrefix(String email) {
        int at = email.indexOf('@');
        String prefix = at > 0 ? email.substring(0, at) : email;
        return prefix.length() > 64 ? prefix.substring(0, 64) : prefix;
    }

    private boolean isEmailFormat(String account) {
        return account != null && EMAIL_PATTERN.matcher(account).matches();
    }

    private String newToken() {
        byte[] bytes = new byte[48];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("会话令牌哈希算法不可用", exception);
        }
    }

    private void initializeProfile(AppUser user) {
        if (user.getPublicId() != null && !user.getPublicId().isBlank()
                && user.getAvatarSeed() != null && !user.getAvatarSeed().isBlank()) {
            return;
        }

        String publicId;
        do {
            publicId = "usr_" + randomUrlSafe(15);
        } while (userRepository.existsByPublicId(publicId));
        user.initializeProfile(publicId, randomUrlSafe(12));
    }

    private String randomUrlSafe(int byteCount) {
        byte[] bytes = new byte[byteCount];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
