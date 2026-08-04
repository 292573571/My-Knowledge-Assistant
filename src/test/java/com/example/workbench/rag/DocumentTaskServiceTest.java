package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.workbench.workspace.DocumentVisibility;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceType;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUserRepository;
import com.example.workbench.workspace.WorkspaceService;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DocumentTaskServiceTest {

    @Test
    void completesClaimedUploadTask() {
        DocumentTaskRepository repository = Mockito.mock(DocumentTaskRepository.class);
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        DocumentTaskEntity task = task();
        when(repository.claim(anyString(), any(), any(), any(), any(), anyString(), any())).thenAnswer(invocation -> {
            task.start();
            return 1;
        });
        when(repository.findById("task-1")).thenReturn(Optional.of(task));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ingestionService.indexWorkspaceUpload(any(), anyString(), anyString(), any()))
                .thenReturn(new WorkspaceDocumentUploadResponse(
                        "document-1", "guide.pdf", "docs/workspaces/team-1/guide.pdf", 3,
                        "team-1", DocumentVisibility.WORKSPACE));
        DocumentTaskService service = service(repository, ingestionService);

        service.process("task-1");

        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.SUCCEEDED);
        assertThat(task.getDocumentId()).isEqualTo("document-1");
        assertThat(task.getProgress()).isEqualTo(100);
    }

    @Test
    void schedulesRetryWhenVectorIndexingFails() {
        DocumentTaskRepository repository = Mockito.mock(DocumentTaskRepository.class);
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        DocumentTaskEntity task = task();
        when(repository.claim(anyString(), any(), any(), any(), any(), anyString(), any())).thenAnswer(invocation -> {
            task.start();
            return 1;
        });
        when(repository.findById("task-1")).thenReturn(Optional.of(task));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ingestionService.indexWorkspaceUpload(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("Failed to write documents to Chroma"));
        DocumentTaskService service = service(repository, ingestionService);

        service.process("task-1");

        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.RETRY_WAIT);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getErrorMessage()).isEqualTo("临时服务不可用，系统将自动重试");
        assertThat(task.getNextAttemptAt()).isNotNull();
    }

    @Test
    void readsVisibleUploadTaskSourceFile() {
        DocumentTaskRepository repository = Mockito.mock(DocumentTaskRepository.class);
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        DocumentTaskEntity task = task();
        DocumentSourceFile source = new DocumentSourceFile("guide.pdf", new byte[]{1, 2, 3});
        when(repository.findById("task-1")).thenReturn(Optional.of(task));
        when(ingestionService.sourceFile(task.getSourcePath(), task.getFileName())).thenReturn(source);
        DocumentTaskService service = service(repository, ingestionService);

        DocumentSourceFile result = service.sourceFile("task-1",
                new WorkspaceAccessContext("viewer-1", "team-1", WorkspaceRole.VIEWER, WorkspaceType.TEAM));

        assertThat(result).isSameAs(source);
    }

    @Test
    void hidesUploadTaskFromOtherWorkspace() {
        DocumentTaskRepository repository = Mockito.mock(DocumentTaskRepository.class);
        when(repository.findById("task-1")).thenReturn(Optional.of(task()));
        DocumentTaskService service = service(repository, Mockito.mock(DocumentIngestionService.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sourceFile("task-1",
                        new WorkspaceAccessContext("viewer-1", "team-2", WorkspaceRole.VIEWER, WorkspaceType.TEAM)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(exception -> assertThat(((org.springframework.web.server.ResponseStatusException) exception)
                        .getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void fallsBackToCurrentIndexedSourceWhenHistoricalUploadWasCleaned() {
        DocumentTaskRepository repository = Mockito.mock(DocumentTaskRepository.class);
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        DocumentTaskEntity task = task();
        task.succeed("document-1");
        WorkspaceAccessContext access = new WorkspaceAccessContext(
                "viewer-1", "team-1", WorkspaceRole.VIEWER, WorkspaceType.TEAM);
        DocumentSourceFile source = new DocumentSourceFile("guide.pdf", new byte[]{4, 5, 6});
        when(repository.findById("task-1")).thenReturn(Optional.of(task));
        when(ingestionService.sourceFile(task.getSourcePath(), task.getFileName()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "文档源文件已缺失"));
        when(ingestionService.isIndexedDocumentAvailable("document-1", access)).thenReturn(true);
        when(ingestionService.sourceFile("document-1", task.getFileName(), access)).thenReturn(source);
        DocumentTaskService service = service(repository, ingestionService);

        assertThat(service.sourceFile("task-1", access)).isSameAs(source);
    }

    @Test
    void marksSucceededUploadAsDeletedWhenIndexNoLongerExists() {
        DocumentTaskRepository repository = Mockito.mock(DocumentTaskRepository.class);
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        DocumentTaskEntity task = task();
        task.succeed("document-1");
        WorkspaceAccessContext access = new WorkspaceAccessContext(
                "viewer-1", "team-1", WorkspaceRole.VIEWER, WorkspaceType.TEAM);
        when(repository.findTop20ByWorkspaceIdOrderByCreatedAtDesc("team-1")).thenReturn(List.of(task));

        List<DocumentTaskResponse> result = service(repository, ingestionService).list(access, false);

        assertThat(result).singleElement().satisfies(response -> {
            assertThat(response.documentId()).isEqualTo("document-1");
            assertThat(response.documentDeleted()).isTrue();
        });
    }

    @Test
    void refusesToOpenDeletedKnowledgeDocumentEvenWhenSourcePathRemains() {
        DocumentTaskRepository repository = Mockito.mock(DocumentTaskRepository.class);
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        DocumentTaskEntity task = task();
        task.succeed("document-1");
        WorkspaceAccessContext access = new WorkspaceAccessContext(
                "viewer-1", "team-1", WorkspaceRole.VIEWER, WorkspaceType.TEAM);
        when(repository.findById("task-1")).thenReturn(Optional.of(task));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service(repository, ingestionService).sourceFile("task-1", access))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(exception -> assertThat(((org.springframework.web.server.ResponseStatusException) exception)
                        .getStatusCode().value()).isEqualTo(410))
                .hasMessageContaining("知识库文档已删除");
        Mockito.verify(ingestionService, Mockito.never()).sourceFile(task.getSourcePath(), task.getFileName());
    }

    private DocumentTaskEntity task() {
        return new DocumentTaskEntity("task-1", "guide.pdf", "docs/workspaces/team-1/guide.pdf", "request-1",
                "user-1", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);
    }

    private DocumentTaskService service(DocumentTaskRepository repository, DocumentIngestionService ingestionService) {
        return new DocumentTaskService(repository, ingestionService, Runnable::run,
                Mockito.mock(AppUserRepository.class), Mockito.mock(WorkspaceService.class),
                Mockito.mock(AdminAuthorizationService.class)) {
            @Override
            WorkspaceAccessContext resolveCurrentAccess(DocumentTaskEntity task) {
                return new WorkspaceAccessContext("user-1", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);
            }
        };
    }
}
