package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 会话历史展示名解析的查询次数约束。
 *
 * <p>历史消息里每条引用都要还原原始文件名，若逐条来源都去查任务表，
 * 打开一次长会话会产生「消息数 × 来源数」次查询。这里锁定快照复用行为。</p>
 */
class DocumentDisplayNameResolverScopeTest {

    private final DocumentTaskRepository taskRepository = mock(DocumentTaskRepository.class);
    private final DocumentDisplayNameResolver resolver = new DocumentDisplayNameResolver(taskRepository);

    @Test
    void reusesPrefetchedTasksAcrossManySources() {
        DocumentTaskEntity task = task("doc-1", "季度复盘.pdf", "docs/季度复盘.pdf");
        when(taskRepository.findByWorkspaceIdAndTypeOrderByCreatedAtDesc("ws-1", DocumentTaskType.UPLOAD))
                .thenReturn(List.of(task));
        List<DocumentIndexEntry> entries = List.of(entry("doc-1", "季度复盘.pdf", "docs/季度复盘.pdf", "ws-1"));

        DocumentDisplayNameResolver.TaskScope scope = resolver.scope("ws-1");
        String first = resolver.resolve("季度复盘.pdf", "docs/季度复盘.pdf", "ws-1", entries, scope);
        String second = resolver.resolve("季度复盘.pdf", "docs/季度复盘.pdf", "ws-1", entries, scope);

        assertThat(first).isEqualTo("季度复盘.pdf");
        assertThat(second).isEqualTo("季度复盘.pdf");
        // 两次解析只允许一次任务表查询。
        verify(taskRepository, times(1))
                .findByWorkspaceIdAndTypeOrderByCreatedAtDesc("ws-1", DocumentTaskType.UPLOAD);
    }

    @Test
    void fallsBackToOwnQueryWhenScopeBelongsToAnotherWorkspace() {
        when(taskRepository.findByWorkspaceIdAndTypeOrderByCreatedAtDesc("ws-2", DocumentTaskType.UPLOAD))
                .thenReturn(List.of());
        DocumentDisplayNameResolver.TaskScope scope = resolver.scope("ws-1");
        verify(taskRepository).findByWorkspaceIdAndTypeOrderByCreatedAtDesc("ws-1", DocumentTaskType.UPLOAD);

        resolver.resolve("报告.pdf", "docs/报告.pdf", "ws-2", List.of(), scope);

        // 快照与请求工作空间不匹配时必须重新查询，避免把别的空间文件名错配过来。
        verify(taskRepository).findByWorkspaceIdAndTypeOrderByCreatedAtDesc(eq("ws-2"), eq(DocumentTaskType.UPLOAD));
    }

    private DocumentTaskEntity task(String documentId, String fileName, String sourcePath) {
        DocumentTaskEntity task = mock(DocumentTaskEntity.class);
        when(task.getDocumentId()).thenReturn(documentId);
        when(task.getFileName()).thenReturn(fileName);
        when(task.getSourcePath()).thenReturn(sourcePath);
        when(task.getCreatedAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        return task;
    }

    private DocumentIndexEntry entry(String documentId, String fileName, String path, String workspaceId) {
        return new DocumentIndexEntry(documentId, fileName, path, "", 1,
                Instant.parse("2026-10-01T00:00:00Z"), "SOURCE", "INDEXED", "", workspaceId,
                com.example.workbench.workspace.DocumentVisibility.WORKSPACE);
    }
}
