package com.example.workbench.rag;

import java.util.List;
import java.util.Set;

public interface ScopedVectorStore extends VectorStore {

    /**
     * 按权限作用域检索文档分块。
     *
     * @param query 检索查询
     * @param topK 最大返回数量
     * @param ownerUserId 当前用户标识，用于私有文档可见性判断
     * @param readableWorkspaceIds 当前用户在该空间下的「有效可读空间集合」（含祖先组织与组织全部子孙），
     *                              用于 WORKSPACE 可见性过滤；为空表示不做空间作用域限制（仅 PUBLIC 与本人 PRIVATE）
     * @return 命中的文档分块
     */
    List<SourceDocument> similaritySearch(String query, int topK, String ownerUserId, Set<String> readableWorkspaceIds);

    /**
     * 兼容旧调用：单个空间标识等价于只读该空间。
     */
    default List<SourceDocument> similaritySearch(String query, int topK, String ownerUserId, String workspaceId) {
        return similaritySearch(query, topK, ownerUserId,
                (workspaceId == null || workspaceId.isBlank()) ? null : Set.of(workspaceId));
    }
}
