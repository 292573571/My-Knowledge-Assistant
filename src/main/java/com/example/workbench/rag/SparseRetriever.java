package com.example.workbench.rag;

import java.util.List;
import java.util.Set;

/**
 * 提供带权限作用域的关键词检索和相邻分块读取能力。
 */
public interface SparseRetriever {

    /**
     * 按关键词检索文档分块。
     *
     * @param query 检索查询
     * @param topK 最大候选数量
     * @param ownerUserId 当前用户标识
     * @param readableWorkspaceIds 当前用户「有效可读空间集合」，用于 WORKSPACE 可见性过滤
     * @return 按稀疏得分降序排列的候选
     */
    List<SourceDocument> search(String query, int topK, String ownerUserId, Set<String> readableWorkspaceIds);

    /**
     * 查询同一文档内目标分块的直接相邻分块。
     *
     * @param documentId 文档标识
     * @param chunkIndex 目标分块序号
     * @param ownerUserId 当前用户标识
     * @param workspaceId 当前知识空间标识
     * @return 权限范围内的相邻分块
     */
    List<SourceDocument> adjacent(String documentId, int chunkIndex, String ownerUserId, String workspaceId);
}
