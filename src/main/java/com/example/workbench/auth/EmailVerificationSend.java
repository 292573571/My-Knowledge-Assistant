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
@Table(name = "email_verification_sends")
@Comment("邮箱验证码发送记录表")
public class EmailVerificationSend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("发送记录主键")
    private Long id;

    @Column(name = "email", nullable = false, length = 320)
    @Comment("验证邮箱")
    private String email;

    @Column(name = "ip_address", nullable = false, length = 64)
    @Comment("发送 IP")
    private String ipAddress;

    @Column(name = "sent_at", nullable = false)
    @Comment("发送时间")
    private Instant sentAt;

    protected EmailVerificationSend() {
    }

    public EmailVerificationSend(String email, String ipAddress, Instant sentAt) {
        this.email = email;
        this.ipAddress = ipAddress;
        this.sentAt = sentAt;
    }
}
