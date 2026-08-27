package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceType;
import org.junit.jupiter.api.Test;

class DocumentTaskEntityTest {

    @Test
    void tracksProgressAndCompletesUploadTask() {
        DocumentTaskEntity task = task();

        task.start();
        task.updateProgress("CHUNKING", 50);
        task.succeed("document-1");

        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.SUCCEEDED);
        assertThat(task.getStage()).isEqualTo("DONE");
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getDocumentId()).isEqualTo("document-1");
        assertThat(task.getFinishedAt()).isNotNull();
    }

    @Test
    void automaticallyRetriesTransientFailuresAndEventuallyFails() {
        DocumentTaskEntity task = task();

        task.start();
        task.fail("embedding timeout", true);
        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.RETRY_WAIT);
        assertThat(task.getNextAttemptAt()).isNotNull();

        task.start();
        task.fail("embedding timeout", true);
        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.RETRY_WAIT);

        task.start();
        task.fail("embedding timeout", true);
        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.FAILED);
        assertThat(task.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void failsImmediatelyForInvalidDocumentsAndAllowsManualRetry() {
        DocumentTaskEntity task = task();

        task.start();
        task.updateProgress("PARSING", 25);
        task.fail("PDF 没有可提取文本", false);
        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.FAILED);
        assertThat(task.getErrorMessage()).contains("PDF");

        task.retry();
        assertThat(task.getStatus()).isEqualTo(DocumentTaskStatus.QUEUED);
        assertThat(task.getProgress()).isEqualTo(5);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getErrorMessage()).isNull();
    }

    @Test
    void rejectsProgressFromAnOldWorkerGeneration() {
        DocumentTaskEntity task = task();
        task.start();
        org.springframework.test.util.ReflectionTestUtils.setField(task, "workerId", "worker-a");
        long generation = task.getGeneration();

        assertThat(task.ownsLease("worker-a", generation)).isTrue();
        task.recoverInterruptedExecution();
        assertThat(task.ownsLease("worker-a", generation)).isFalse();
        assertThat(task.getGeneration()).isGreaterThan(generation);
    }

    private DocumentTaskEntity task() {
        return new DocumentTaskEntity("task-1", "guide.pdf", "docs/workspaces/team-1/guide.pdf", "request-1",
                "user-1", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);
    }
}
