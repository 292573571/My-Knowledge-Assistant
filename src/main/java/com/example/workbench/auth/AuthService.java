package com.example.workbench.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AuthService {

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Duration sessionDuration;

    public AuthService(
            AppUserRepository userRepository,
            UserSessionRepository sessionRepository,
            @Value("${app.auth.session-hours:168}") long sessionHours
    ) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.sessionDuration = Duration.ofHours(Math.max(1, sessionHours));
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String account = normalizeAccount(request.account());
        if ("admin".equals(account)) {
            throw new IllegalArgumentException("admin 为系统保留账号，不能通过注册接口创建");
        }
        if (userRepository.findByAccount(account).isPresent()) {
            log.warn("User registration rejected reason=account_already_registered");
            throw new IllegalArgumentException("account is already registered");
        }

        AppUser user = new AppUser(account, account, passwordEncoder.encode(request.password()));
        initializeProfile(user);
        user = userRepository.save(user);
        log.info("User registered userId={}", user.getId());
        return createSession(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String account = normalizeAccount(request.account());
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

        UserSession session = sessionRepository.findByToken(token)
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
            sessionRepository.deleteByToken(token);
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
        sessionRepository.deleteOtherSessions(user.getId(), currentToken);
        log.info("Password changed userId={} otherSessionsRevoked=true", user.getId());
    }

    private AuthResponse createSession(AppUser user) {
        String token = newToken();
        sessionRepository.save(new UserSession(user, token, Instant.now().plus(sessionDuration)));
        log.info("User session created userId={} expiresInHours={}", user.getId(), sessionDuration.toHours());
        initializeProfile(user);
        return new AuthResponse(token, user.getAccount(), user.getUserName(), user.getPublicId(), "/api/auth/avatar",
                user.getCreatedAt(), user.getSystemRole());
    }

    private String normalizeAccount(String account) {
        return account.trim().toLowerCase();
    }

    private String newToken() {
        byte[] bytes = new byte[48];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
