package com.example.workbench.workspace;

import com.example.workbench.auth.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "workspaces")
@Comment("知识空间表")
public class Workspace {

    @Id
    @Column(length = 36)
    @Comment("空间主键")
    private String id;

    @Column(nullable = false, length = 80)
    @Comment("空间名称")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Comment("空间类型：个人、团队或公共")
    private WorkspaceType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    @Comment("空间所有者用户主键")
    private AppUser owner;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt;

    protected Workspace() {
    }

    public Workspace(String name, WorkspaceType type, AppUser owner) {
        this(UUID.randomUUID().toString(), name, type, owner);
    }

    public Workspace(String id, String name, WorkspaceType type, AppUser owner) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.owner = owner;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public WorkspaceType getType() { return type; }
    public AppUser getOwner() { return owner; }
    public Instant getCreatedAt() { return createdAt; }
}
