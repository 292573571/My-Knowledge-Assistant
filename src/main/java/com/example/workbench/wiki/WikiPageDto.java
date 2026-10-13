package com.example.workbench.wiki;

import java.time.Instant;

/**
 * wiki 页面的对外传输对象。
 */
public record WikiPageDto(
        String id,
        String workspaceId,
        String parentId,
        String title,
        String slug,
        String contentMd,
        String ownerId,
        Instant createdAt,
        Instant updatedAt
) {
    public static WikiPageDto from(WikiPageEntity entity) {
        return new WikiPageDto(
                entity.getId(),
                entity.getWorkspaceId(),
                entity.getParentId(),
                entity.getTitle(),
                entity.getSlug(),
                entity.getContentMd(),
                entity.getOwnerId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
