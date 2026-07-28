package com.example.workbench.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account", nullable = false, unique = true, length = 64)
    private String account;

    @Column(name = "user_name", nullable = false, length = 64)
    private String userName;

    @Column(name = "public_id", unique = true, length = 32)
    private String publicId;

    @Column(name = "avatar_seed", length = 32)
    private String avatarSeed;

    @Column(name = "avatar_file_name", length = 128)
    private String avatarFileName;

    @Column(name = "avatar_content_type", length = 64)
    private String avatarContentType;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    public AppUser(String account, String userName, String passwordHash) {
        this.account = account;
        this.userName = userName;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getAccount() {
        return account;
    }

    public String getUserName() {
        return userName;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getAvatarSeed() {
        return avatarSeed;
    }

    public String getAvatarFileName() {
        return avatarFileName;
    }

    public String getAvatarContentType() {
        return avatarContentType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void initializeProfile(String publicId, String avatarSeed) {
        if (this.publicId == null || this.publicId.isBlank()) {
            this.publicId = publicId;
        }
        if (this.avatarSeed == null || this.avatarSeed.isBlank()) {
            this.avatarSeed = avatarSeed;
        }
    }

    public void changeUserName(String userName) {
        this.userName = userName;
    }

    public void changeAvatar(String avatarFileName, String avatarContentType) {
        this.avatarFileName = avatarFileName;
        this.avatarContentType = avatarContentType;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
