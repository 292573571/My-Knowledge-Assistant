package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import com.example.workbench.workspace.DocumentVisibility;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagServiceSourceNameTest {

    @Test
    void resolvesOriginalFileNameWhenVectorMetadataUsesGeneratedStorageName() {
        RagService service = new RagService(mock(DocumentIngestionService.class), mock(VectorStore.class),
                mock(LocalChatClient.class), new ConversationMemory(), mock(WebSearchService.class),
                new RagQualityGate(mock(LocalChatClient.class), false), true, 5, 0.85, "distance",
                false, false, 3, false, false);
        SourceDocument source = new SourceDocument(
                "legacy#chunk-0", "content", "title", "storage-id.pdf", "docs/storage-id.pdf", 0,
                "legacy-id", "storage-id.pdf", "content-hash", 0.2, "", 0, 0, 7, "pdf-page",
                "SOURCE", "user-1", "workspace-a", DocumentVisibility.WORKSPACE, 11);
        DocumentIndexEntry indexed = new DocumentIndexEntry("legacy-id", "redis-cache.pdf", "docs/storage-id.pdf",
                "content-hash", 1, 1L, "SOURCE", "INDEXED", "user-1", "workspace-a",
                DocumentVisibility.WORKSPACE);

        assertThat(service.originalSourceFileName(source, List.of(indexed))).isEqualTo("redis-cache.pdf");
    }

    @Test
    void resolvesOriginalFileNameWhenLegacyVectorIdentifiersDoNotMatch() {
        RagService service = new RagService(mock(DocumentIngestionService.class), mock(VectorStore.class),
                mock(LocalChatClient.class), new ConversationMemory(), mock(WebSearchService.class),
                new RagQualityGate(mock(LocalChatClient.class), false), true, 5, 0.85, "distance",
                false, false, 3, false, false);
        SourceDocument source = new SourceDocument(
                "old#chunk-0", "content", "title", "636d4aed-7851-4d3b-a0d4-0aaca09eff4b.pdf",
                "legacy/636d4aed-7851-4d3b-a0d4-0aaca09eff4b.pdf", 0, "old-id", "636d4aed-7851-4d3b-a0d4-0aaca09eff4b.pdf",
                "old-hash", 0.2, "", 0, 0, 7, "pdf-page", "SOURCE", "user-1", "workspace-a",
                DocumentVisibility.WORKSPACE, 11);
        DocumentIndexEntry indexed = new DocumentIndexEntry("new-id", "redis-cache.pdf",
                "docs/636d4aed-7851-4d3b-a0d4-0aaca09eff4b.pdf", "new-hash", 1, 2L,
                "SOURCE", "INDEXED", "user-1", "workspace-a", DocumentVisibility.WORKSPACE);

        assertThat(service.originalSourceFileName(source, List.of(indexed))).isEqualTo("redis-cache.pdf");
    }

    @Test
    void prefersOriginalNameFromExactPhysicalPathWhenOldIndexAlsoMatchesDocumentId() {
        RagService service = new RagService(mock(DocumentIngestionService.class), mock(VectorStore.class),
                mock(LocalChatClient.class), new ConversationMemory(), mock(WebSearchService.class),
                new RagQualityGate(mock(LocalChatClient.class), false), true, 5, 0.85, "distance",
                false, false, 3, false, false);
        String storageName = "636d4aed-7851-4d3b-a0d4-0aaca09eff4b.pdf";
        String path = "docs/workspaces/d574059c-1a50-49af-904a-494a3c044d9f/" + storageName;
        SourceDocument source = new SourceDocument("chunk", "content", "Agent", storageName, path, 20,
                "shared-id", storageName, "hash", 0.64, "", 0, 0, 7, "pdf-page", "SOURCE",
                "user-1", "workspace-a", DocumentVisibility.WORKSPACE, 20);
        DocumentIndexEntry old = new DocumentIndexEntry("shared-id", storageName, "legacy/old.pdf", "old-hash",
                1, 1L, "SOURCE", "INDEXED", "user-1", "workspace-a", DocumentVisibility.WORKSPACE);
        DocumentIndexEntry current = new DocumentIndexEntry("new-id", "AI-Agents-in-Depth-zh-CN.pdf", path,
                "new-hash", 598, 2L, "SOURCE", "INDEXED", "user-1", "workspace-a",
                DocumentVisibility.WORKSPACE);

        assertThat(service.originalSourceFileName(source, List.of(old, current)))
                .isEqualTo("AI-Agents-in-Depth-zh-CN.pdf");
    }
}
