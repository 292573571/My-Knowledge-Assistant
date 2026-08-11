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
                "docs/workspaces/workspace-a/" + generated, "hash", 1, 2L, "SOURCE", "INDEXED",
                "user", "workspace-a", DocumentVisibility.WORKSPACE);

        assertThat(resolver.resolve(source, List.of(indexed))).isEqualTo("真实资料.pdf");
    }
}
