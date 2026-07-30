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
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "chat_conversations")
@Comment("聊天会话表")
public class ChatConversation {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    @Comment("会话主键")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @Comment("所属用户主键")
    private AppUser user;

    @Column(name = "title", nullable = false, length = 120)
    @Comment("会话标题")
    private String title;

    @Column(name = "mode", nullable = false, length = 24)
    @Comment("会话模式")
    private String mode;

    @Column(name = "workspace_id", length = 36)
    @Comment("所属知识空间主键，空值表示历史个人空间会话")
    private String workspaceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("最后更新时间")
    private Instant updatedAt;

    protected ChatConversation() {
    }

    public ChatConversation(String id, AppUser user, String title, String mode) {
        this(id, user, title, mode, null);
    }

    public ChatConversation(String id, AppUser user, String title, String mode, String workspaceId) {
        this.id = id;
        this.user = user;
        this.title = title;
        this.mode = mode;
        this.workspaceId = workspaceId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public String getId() { return id; }
    public AppUser getUser() { return user; }
    public String getTitle() { return title; }
    public String getMode() { return mode; }
    public String getWorkspaceId() { return workspaceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void touch(String title, String mode) {
        if (title != null && !title.isBlank()) this.title = title;
        if (mode != null && !mode.isBlank()) this.mode = mode;
        this.updatedAt = Instant.now();
    }
}
