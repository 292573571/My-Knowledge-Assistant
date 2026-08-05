package com.example.workbench.rag;

/**
 * 空间索引重建的逐文件进度。
 *
 * @param totalFiles 计划处理文件数
 * @param completedFiles 已处理文件数
 * @param succeededFiles 成功文件数
 * @param failedFiles 失败文件数
 * @param chunks 已生成分块数
 */
public record RebuildProgress(
        int totalFiles,
        int completedFiles,
        int succeededFiles,
        int failedFiles,
        int chunks
) {
}
