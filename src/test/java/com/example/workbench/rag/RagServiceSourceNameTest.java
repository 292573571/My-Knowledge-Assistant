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
}
