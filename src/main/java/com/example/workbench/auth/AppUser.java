package com.example.workbench.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "app_users")
@Comment("应用用户表")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("用户主键")
    private Long id;

    @Column(name = "account", nullable = false, unique = true, length = 64)
    @Comment("登录账号")
    private String account;

    @Column(name = "user_name", nullable = false, length = 64)
    @Comment("用户显示名称")
    private String userName;

    @Column(name = "public_id", unique = true, length = 32)
    @Comment("对外公开用户标识")
    private String publicId;

    @Column(name = "avatar_seed", length = 32)
    @Comment("默认头像生成种子")
    private String avatarSeed;

    @Column(name = "avatar_file_name", length = 128)
    @Comment("头像存储文件名")
    private String avatarFileName;

    @Column(name = "avatar_content_type", length = 64)
    @Comment("头像媒体类型")
    private String avatarContentType;

    @Column(name = "password_hash", nullable = false, length = 100)
    @Comment("密码哈希")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_role", nullable = false, length = 24,
            columnDefinition = "varchar(24) default 'USER'")
    @Comment("系统角色：普通用户、管理员或超级管理员")
    private SystemRole systemRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt;

    protected AppUser() {
    }

    public AppUser(String account, String userName, String passwordHash) {
        this.account = account;
        this.userName = userName;
        this.passwordHash = passwordHash;
        this.systemRole = "admin".equalsIgnoreCase(account) ? SystemRole.SUPER_ADMIN : SystemRole.USER;
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

    public SystemRole getSystemRole() {
        if ("admin".equalsIgnoreCase(account)) {
            return SystemRole.SUPER_ADMIN;
        }
        return systemRole == null ? SystemRole.USER : systemRole;
    }

    public void changeSystemRole(SystemRole systemRole) {
        this.systemRole = "admin".equalsIgnoreCase(account) ? SystemRole.SUPER_ADMIN : systemRole;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
