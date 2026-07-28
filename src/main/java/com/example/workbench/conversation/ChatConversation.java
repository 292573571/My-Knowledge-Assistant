package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "chat_conversations")
public class ChatConversation {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "mode", nullable = false, length = 24)
    private String mode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatConversation() {
    }

    public ChatConversation(String id, AppUser user, String title, String mode) {
        this.id = id;
        this.user = user;
        this.title = title;
        this.mode = mode;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public String getId() { return id; }
    public AppUser getUser() { return user; }
    public String getTitle() { return title; }
    public String getMode() { return mode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void touch(String title, String mode) {
        if (title != null && !title.isBlank()) this.title = title;
        if (mode != null && !mode.isBlank()) this.mode = mode;
        this.updatedAt = Instant.now();
    }
}
