package com.example.workbench.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "email_verification_codes")
@Comment("邮箱验证码表")
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("验证码主键")
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    @Comment("验证邮箱")
    private String email;

    @Column(name = "code_hash", nullable = false, length = 64)
    @Comment("验证码哈希")
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    @Comment("验证码过期时间")
    private Instant expiresAt;

    @Column(name = "last_sent_at", nullable = false)
    @Comment("最近发送时间")
    private Instant lastSentAt;

    @Column(name = "last_sent_ip", nullable = false, length = 64)
    @Comment("最近发送 IP")
    private String lastSentIp;

    @Column(name = "failed_attempts", nullable = false)
    @Comment("连续验证失败次数")
    private int failedAttempts;

    protected EmailVerificationCode() {
    }

    public EmailVerificationCode(String email, String codeHash, Instant expiresAt, Instant lastSentAt, String lastSentIp) {
        this.email = email;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.lastSentAt = lastSentAt;
        this.lastSentIp = lastSentIp;
        this.failedAttempts = 0;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void renew(String codeHash, Instant expiresAt, Instant sentAt, String sentIp) {
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.lastSentAt = sentAt;
        this.lastSentIp = sentIp;
        this.failedAttempts = 0;
    }

    public void recordFailure() {
        this.failedAttempts++;
    }
}
