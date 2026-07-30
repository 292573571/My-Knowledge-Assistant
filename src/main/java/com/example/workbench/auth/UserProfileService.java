package com.example.workbench.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserProfileService {

    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp"
    );
    private static final String[] COLORS = {"#4f6fe8", "#7c4dff", "#168b75", "#d45c78", "#db7b2b", "#3868b3"};

    private final AppUserRepository userRepository;
    private final Path avatarDirectory;
    private final AdminAuthorizationService adminAuthorizationService;

    public UserProfileService(
            AppUserRepository userRepository,
            AdminAuthorizationService adminAuthorizationService,
            @Value("${app.user.avatar-directory:data/avatars}") String avatarDirectory
    ) {
        this.userRepository = userRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.avatarDirectory = Path.of(avatarDirectory).toAbsolutePath().normalize();
    }

    @Transactional
    public CurrentUserResponse update(AppUser user, UpdateProfileRequest request) {
        String userName = request.userName().strip();
        user.changeUserName(userName);
        userRepository.save(user);
        return response(user);
    }

    @Transactional
    public CurrentUserResponse uploadAvatar(AppUser user, MultipartFile file) {
        byte[] content = readAndValidate(file);
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String fileName = UUID.randomUUID() + EXTENSIONS.get(contentType);
        Path target = avatarDirectory.resolve(fileName).normalize();
        if (!target.startsWith(avatarDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像文件名无效");
        }

        String previousFileName = user.getAvatarFileName();
        try {
            Files.createDirectories(avatarDirectory);
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
            user.changeAvatar(fileName, contentType);
            userRepository.save(user);
            deletePreviousAvatar(previousFileName, fileName);
            return response(user);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save user avatar", exception);
        }
    }

    public UserAvatar avatar(AppUser user) {
        if (user.getAvatarFileName() == null || user.getAvatarFileName().isBlank()) {
            return defaultAvatar(user);
        }

        Path avatar = avatarDirectory.resolve(user.getAvatarFileName()).normalize();
        if (!avatar.startsWith(avatarDirectory) || !Files.isRegularFile(avatar)) {
            return defaultAvatar(user);
        }
        try {
            return new UserAvatar(Files.readAllBytes(avatar), user.getAvatarContentType());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read user avatar", exception);
        }
    }

    private byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择头像文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像不能超过 2 MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!EXTENSIONS.containsKey(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像仅支持 JPEG、PNG 或 WebP");
        }
        try {
            byte[] content = file.getBytes();
            if (!hasValidSignature(contentType, content)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像文件内容与格式不匹配");
            }
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read uploaded avatar", exception);
        }
    }

    private boolean hasValidSignature(String contentType, byte[] content) {
        if ("image/jpeg".equals(contentType)) {
            return content.length >= 3 && content[0] == (byte) 0xff && content[1] == (byte) 0xd8 && content[2] == (byte) 0xff;
        }
        if ("image/png".equals(contentType)) {
            byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            return content.length >= signature.length && java.util.Arrays.equals(signature, java.util.Arrays.copyOf(content, signature.length));
        }
        return content.length >= 12
                && new String(content, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(content, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
    }

    private UserAvatar defaultAvatar(AppUser user) {
        String seed = user.getAvatarSeed() == null ? user.getAccount() : user.getAvatarSeed();
        byte[] digest = sha256(seed);
        String first = COLORS[Byte.toUnsignedInt(digest[0]) % COLORS.length];
        String second = COLORS[Byte.toUnsignedInt(digest[1]) % COLORS.length];
        String initial = user.getUserName().isBlank() ? "U" : escapeXml(user.getUserName().substring(0, 1).toUpperCase());
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">
                  <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient></defs>
                  <rect width="128" height="128" rx="64" fill="url(#g)"/>
                  <circle cx="94" cy="30" r="28" fill="#fff" opacity=".12"/>
                  <text x="64" y="77" text-anchor="middle" fill="#fff" font-family="Arial,sans-serif" font-size="52" font-weight="700">%s</text>
                </svg>
                """.formatted(first, second, initial);
        return new UserAvatar(svg.getBytes(StandardCharsets.UTF_8), "image/svg+xml");
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void deletePreviousAvatar(String previousFileName, String nextFileName) {
        if (previousFileName == null || previousFileName.equals(nextFileName)) {
            return;
        }
        try {
            Path previousAvatar = avatarDirectory.resolve(previousFileName).normalize();
            if (previousAvatar.startsWith(avatarDirectory)) {
                Files.deleteIfExists(previousAvatar);
            }
        } catch (IOException ignored) {
            // A stale avatar file does not invalidate the successful profile update.
        }
    }

    private CurrentUserResponse response(AppUser user) {
        return new CurrentUserResponse(user.getAccount(), user.getUserName(), user.getPublicId(), "/api/auth/avatar",
                user.getCreatedAt(), adminAuthorizationService.effectiveRole(user));
    }
}
