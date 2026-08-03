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
