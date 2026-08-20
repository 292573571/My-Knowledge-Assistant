package com.example.workbench.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserProfileService userProfileService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final EmailVerificationService emailVerificationService;
    private final boolean secureCookie;
    private final Duration sessionDuration;

    public AuthController(AuthService authService, UserProfileService userProfileService,
                          AdminAuthorizationService adminAuthorizationService,
                          EmailVerificationService emailVerificationService,
                          @Value("${app.auth.cookie-secure:true}") boolean secureCookie,
                          @Value("${app.auth.session-hours:168}") long sessionHours) {
        this.authService = authService;
        this.userProfileService = userProfileService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.emailVerificationService = emailVerificationService;
        this.secureCookie = secureCookie;
        this.sessionDuration = Duration.ofHours(sessionHours);
    }

    @PostMapping("/send-code")
    public RegisterResultResponse sendCode(@Valid @RequestBody SendCodeRequest request, HttpServletRequest httpRequest) {
        emailVerificationService.send(request.email(), httpRequest.getRemoteAddr());
        return new RegisterResultResponse("验证码已发送，请查收邮件");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResultResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public CurrentUserResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return authenticated(authService.login(request), response);
    }

    @GetMapping("/me")
    public CurrentUserResponse me(HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        return profile(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout((String) request.getAttribute(AuthFilter.AUTH_TOKEN_ATTRIBUTE));
        response.addHeader("Set-Cookie", sessionCookie("", Duration.ZERO).toString());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        authService.changePassword(
                (AppUser) httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE),
                (String) httpRequest.getAttribute(AuthFilter.AUTH_TOKEN_ATTRIBUTE),
                request
        );
    }

    @PutMapping("/profile")
    public CurrentUserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request, HttpServletRequest httpRequest) {
        return userProfileService.update(user(httpRequest), request);
    }

    @PostMapping(path = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CurrentUserResponse uploadAvatar(@RequestPart("file") MultipartFile file, HttpServletRequest httpRequest) {
        return userProfileService.uploadAvatar(user(httpRequest), file);
    }

    @GetMapping("/avatar")
    public ResponseEntity<byte[]> avatar(HttpServletRequest request) {
        UserAvatar avatar = userProfileService.avatar(user(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .body(avatar.content());
    }

    private CurrentUserResponse profile(AppUser user) {
        return new CurrentUserResponse(user.getAccount(), user.getEmail(), user.getPhone(), user.getUserName(), user.getPublicId(), "/api/auth/avatar",
                user.getCreatedAt(), adminAuthorizationService.effectiveRole(user));
    }

    private CurrentUserResponse authenticated(AuthResponse authentication, HttpServletResponse response) {
        response.addHeader("Set-Cookie", sessionCookie(authentication.token(), sessionDuration).toString());
        return new CurrentUserResponse(authentication.account(), authentication.email(), authentication.phone(),
                authentication.userName(), authentication.publicId(),
                authentication.avatarUrl(), authentication.createdAt(), effectiveRole(authentication));
    }

    private SystemRole effectiveRole(AuthResponse authentication) {
        return adminAuthorizationService.effectiveRole(authentication.account(), authentication.systemRole());
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(AuthFilter.SESSION_COOKIE, value).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/").maxAge(maxAge).build();
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
