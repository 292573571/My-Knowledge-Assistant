package com.example.workbench.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "maintenance_pending_actions")
@Comment("维护 Agent 待确认动作表")
class MaintenancePendingActionEntity {

    @Id
    @Column(name = "confirmation_token", length = 36)
    @Comment("确认令牌")
    private String confirmationToken;

    @Column(name = "user_id", nullable = false, length = 64)
    @Comment("发起用户标识")
    private String userId;

    @Column(name = "workspace_id", nullable = false, length = 36)
    @Comment("所属知识空间")
    private String workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Comment("待执行维护动作")
    private MaintenanceAction action;

    @Column(name = "target_id", nullable = false, length = 128)
    @Comment("动作目标标识")
    private String targetId;

    @Column(nullable = false, length = 1000)
    @Comment("确认提示描述")
    private String description;

    @Column(name = "expires_at", nullable = false)
    @Comment("确认令牌过期时间")
    private Instant expiresAt;

    protected MaintenancePendingActionEntity() {
    }

    MaintenancePendingActionEntity(MaintenancePendingActionState state) {
        this.confirmationToken = state.token;
        this.userId = state.userId;
        this.workspaceId = state.workspaceId;
        this.action = state.action;
        this.targetId = state.targetId;
        this.description = state.description;
        this.expiresAt = state.expiresAt;
    }

    MaintenancePendingActionState state() {
        return new MaintenancePendingActionState(confirmationToken, userId, workspaceId, action,
                targetId, description, expiresAt);
    }
}
