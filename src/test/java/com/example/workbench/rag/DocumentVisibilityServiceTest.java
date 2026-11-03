package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.workspace.DocumentVisibility;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DocumentVisibilityServiceTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AdminAuthorizationService adminAuthorizationService = mock(AdminAuthorizationService.class);
    private final DocumentIndexRepository indexRepository = mock(DocumentIndexRepository.class);
    private final DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
    private final ChromaVectorStoreAdapter vectorStore = mock(ChromaVectorStoreAdapter.class);
    private final DocumentVisibilityService service = new DocumentVisibilityService(
            workspaceService, adminAuthorizationService, indexRepository, chunkRepository, vectorStore);

    private final AppUser alice = new AppUser("alice", "爱丽丝", "hash");
    private final AppUser admin = new AppUser("admin", "管理员", "hash");

    private DocumentIndexEntity indexOf(DocumentVisibility visibility, String ownerUserId) {
        return new DocumentIndexEntity(new DocumentIndexEntry("doc-1", "notes.md", "docs/notes.md", "hash-1",
                2, Instant.now(), "SOURCE", "INDEXED", ownerUserId, "ws-1", visibility));
    }

    private DocumentChunkEntity chunkOf(int index) {
        SourceDocument source = new SourceDocument("doc-1#chunk-" + index, "内容 " + index, "标题", "notes.md",
                "docs/notes.md", index, "doc-1", "notes.md", "hash-1", 0.0, "标题", 1, 0, 10,
                "text-paragraph", "SOURCE", "u1", "ws-1", DocumentVisibility.WORKSPACE);
        return new DocumentChunkEntity(source);
    }

    private void givenAccess(WorkspaceRole role) {
        when(workspaceService.access(alice, "ws-1"))
                .thenReturn(new WorkspaceAccessContext("u1", "ws-1", role));
    }

    @Test
    void editorCanRestrictDocumentToPrivateAndVectorMetadataIsRefreshed() {
        givenAccess(WorkspaceRole.EDITOR);
        when(indexRepository.findByDocumentIdAndWorkspaceId("doc-1", "ws-1"))
                .thenReturn(Optional.of(indexOf(DocumentVisibility.WORKSPACE, "u1")));
        when(chunkRepository.findByDocumentIdAndWorkspaceIdOrderByChunkIndex("doc-1", "ws-1"))
                .thenReturn(List.of(chunkOf(0), chunkOf(1)));

        DocumentIndexEntry updated = service.change(alice, "doc-1", "ws-1", DocumentVisibility.PRIVATE);

        assertThat(updated.visibility()).isEqualTo(DocumentVisibility.PRIVATE);
        verify(vectorStore).refreshVisibilityMetadata(any());
        verify(indexRepository).save(any(DocumentIndexEntity.class));
        verify(chunkRepository).saveAll(any());
    }

    @Test
    void viewerCannotChangeVisibility() {
        givenAccess(WorkspaceRole.VIEWER);
        when(indexRepository.findByDocumentIdAndWorkspaceId("doc-1", "ws-1"))
                .thenReturn(Optional.of(indexOf(DocumentVisibility.WORKSPACE, "u-other")));

        assertThatThrownBy(() -> service.change(alice, "doc-1", "ws-1", DocumentVisibility.PRIVATE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("权限");
        verify(vectorStore, never()).refreshVisibilityMetadata(any());
    }

    @Test
    void viewerWhoOwnsDocumentCanStillMakeItPrivate() {
        givenAccess(WorkspaceRole.VIEWER);
        when(indexRepository.findByDocumentIdAndWorkspaceId("doc-1", "ws-1"))
                .thenReturn(Optional.of(indexOf(DocumentVisibility.WORKSPACE, "u1")));
        when(chunkRepository.findByDocumentIdAndWorkspaceIdOrderByChunkIndex("doc-1", "ws-1"))
                .thenReturn(List.of(chunkOf(0)));

        DocumentIndexEntry updated = service.change(alice, "doc-1", "ws-1", DocumentVisibility.PRIVATE);

        assertThat(updated.visibility()).isEqualTo(DocumentVisibility.PRIVATE);
        verify(vectorStore).refreshVisibilityMetadata(any());
    }

    @Test
    void publishingToPublicRequiresAdmin() {
        givenAccess(WorkspaceRole.OWNER);
        when(indexRepository.findByDocumentIdAndWorkspaceId("doc-1", "ws-1"))
                .thenReturn(Optional.of(indexOf(DocumentVisibility.WORKSPACE, "u1")));
        when(adminAuthorizationService.isAdmin(alice)).thenReturn(false);

        assertThatThrownBy(() -> service.change(alice, "doc-1", "ws-1", DocumentVisibility.PUBLIC))
                .isInstanceOf(ResponseStatusException.class);
        verify(vectorStore, never()).refreshVisibilityMetadata(any());
    }

    @Test
    void adminCanPublishToPublic() {
        when(workspaceService.access(admin, "ws-1"))
                .thenReturn(new WorkspaceAccessContext("u-admin", "ws-1", WorkspaceRole.OWNER));
        when(indexRepository.findByDocumentIdAndWorkspaceId("doc-1", "ws-1"))
                .thenReturn(Optional.of(indexOf(DocumentVisibility.WORKSPACE, "u1")));
        when(adminAuthorizationService.isAdmin(admin)).thenReturn(true);
        when(chunkRepository.findByDocumentIdAndWorkspaceIdOrderByChunkIndex(anyString(), anyString()))
                .thenReturn(List.of(chunkOf(0)));

        DocumentIndexEntry updated = service.change(admin, "doc-1", "ws-1", DocumentVisibility.PUBLIC);

        assertThat(updated.visibility()).isEqualTo(DocumentVisibility.PUBLIC);
        verify(vectorStore).refreshVisibilityMetadata(any());
    }

    @Test
    void sameVisibilityIsNoOp() {
        givenAccess(WorkspaceRole.EDITOR);
        when(indexRepository.findByDocumentIdAndWorkspaceId("doc-1", "ws-1"))
                .thenReturn(Optional.of(indexOf(DocumentVisibility.WORKSPACE, "u1")));

        DocumentIndexEntry updated = service.change(alice, "doc-1", "ws-1", DocumentVisibility.WORKSPACE);

        assertThat(updated.visibility()).isEqualTo(DocumentVisibility.WORKSPACE);
        verify(indexRepository, never()).save(any());
        verify(vectorStore, never()).refreshVisibilityMetadata(any());
    }

    @Test
    void unknownDocumentIsNotFound() {
        givenAccess(WorkspaceRole.EDITOR);
        when(indexRepository.findByDocumentIdAndWorkspaceId("missing", "ws-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.change(alice, "missing", "ws-1", DocumentVisibility.PRIVATE))
                .isInstanceOf(ResponseStatusException.class);
    }
}
