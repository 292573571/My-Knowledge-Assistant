package com.example.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class UserProfileServiceTest {

    @TempDir
    Path tempDir;

    private AppUserRepository users;
    private UserProfileService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(AppUserRepository.class);
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new UserProfileService(users, new AdminAuthorizationService(""), tempDir.resolve("avatars").toString());
        user = new AppUser("alice", "alice", "hash");
        user.initializeProfile("usr_public-id", "avatar-seed");
    }

    @Test
    void updatesDisplayNameWithoutChangingAccount() {
        CurrentUserResponse response = service.update(user, new UpdateProfileRequest("Alice Zhang", null));

        assertThat(response.userName()).isEqualTo("Alice Zhang");
        assertThat(response.account()).isEqualTo("alice");
        assertThat(user.getAccount()).isEqualTo("alice");
    }

    @Test
    void returnsDeterministicSvgWhenNoAvatarWasUploaded() {
        UserAvatar first = service.avatar(user);
        UserAvatar second = service.avatar(user);

        assertThat(first.contentType()).isEqualTo("image/svg+xml");
        assertThat(first.content()).isEqualTo(second.content());
        assertThat(new String(first.content(), java.nio.charset.StandardCharsets.UTF_8)).contains(">A</text>");
    }

    @Test
    void uploadsValidatedPngWithRandomServerFileName() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "../avatar.png", "image/png", png);

        service.uploadAvatar(user, file);

        assertThat(user.getAvatarFileName()).endsWith(".png").doesNotContain("..");
        assertThat(Files.readAllBytes(tempDir.resolve("avatars").resolve(user.getAvatarFileName()))).isEqualTo(png);
        assertThat(service.avatar(user).content()).isEqualTo(png);
    }

    @Test
    void rejectsAFileWhoseContentDoesNotMatchItsImageType() {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "not-an-image".getBytes());

        assertThatThrownBy(() -> service.uploadAvatar(user, file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("头像文件内容与格式不匹配");
    }
}
