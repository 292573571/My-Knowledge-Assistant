package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.workbench.workspace.DocumentVisibility;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentDisplayNameResolverTest {

    @Test
    void usesIndexedOriginalNameForAnyGeneratedPhysicalFilename() {
        DocumentDisplayNameResolver resolver = new DocumentDisplayNameResolver(mock(DocumentTaskRepository.class));
        String generated = "636d4aed-7851-4d3b-a0d4-0aaca09eff4b.pdf";
        SourceDocument source = new SourceDocument("chunk", "", "", generated,
                "docs/workspaces/workspace-a/" + generated, 0);
        DocumentIndexEntry indexed = new DocumentIndexEntry("doc", "真实资料.pdf",
                "docs/workspaces/workspace-a/" + generated, "hash", 1, java.time.Instant.ofEpochMilli(2L), "SOURCE", "INDEXED",
                "user", "workspace-a", DocumentVisibility.WORKSPACE);

        assertThat(resolver.resolve(source, List.of(indexed))).isEqualTo("真实资料.pdf");
    }

    @Test
    void matchesAbsoluteAndRelativePathsWithoutDependingOnOneStorageLayout() {
        DocumentDisplayNameResolver resolver = new DocumentDisplayNameResolver(mock(DocumentTaskRepository.class));
        String generated = "636d4aed-7851-4d3b-a0d4-0aaca09eff4b.pdf";
        SourceDocument source = new SourceDocument("old-id", "", "", generated,
                "/srv/app/docs/workspaces/ws/" + generated, 0);
        DocumentIndexEntry indexed = new DocumentIndexEntry("new-id", "原始资料.pdf",
                "docs/workspaces/ws/" + generated, "different-hash", 1, java.time.Instant.ofEpochMilli(2L),
                "SOURCE", "INDEXED", "user", "ws", DocumentVisibility.WORKSPACE);

        assertThat(resolver.resolve(source, List.of(indexed))).isEqualTo("原始资料.pdf");
    }

    @Test
    void doesNotUseAnIdenticallyNamedFileFromAnotherWorkspace() {
        DocumentDisplayNameResolver resolver = new DocumentDisplayNameResolver(mock(DocumentTaskRepository.class));
        String generated = "636d4aed-7851-4d3b-a0d4-0aaca09eff4b.pdf";
        SourceDocument source = new SourceDocument("chunk", "", "", generated,
                "docs/workspaces/target/" + generated, 0, "", generated, "", 0, "", 0, 0, 0,
                "pdf-page", "SOURCE", "user", "target", DocumentVisibility.WORKSPACE, 1);
        DocumentIndexEntry otherWorkspace = new DocumentIndexEntry("doc", "其他空间.pdf",
                "docs/workspaces/other/" + generated, "hash", 1, java.time.Instant.ofEpochMilli(2L),
                "SOURCE", "INDEXED", "user", "other", DocumentVisibility.WORKSPACE);

        assertThat(resolver.resolve(source, List.of(otherWorkspace))).isEqualTo("知识库文档");
    }
}
