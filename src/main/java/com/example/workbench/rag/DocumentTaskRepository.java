package com.example.workbench.rag;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface DocumentTaskRepository extends JpaRepository<DocumentTaskEntity, String> {
    List<DocumentTaskEntity> findTop20ByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    List<DocumentTaskEntity> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            Collection<DocumentTaskStatus> statuses, Instant nextAttemptAt);
    List<DocumentTaskEntity> findByStatus(DocumentTaskStatus status);
    List<DocumentTaskEntity> findByStatusAndWorkerId(DocumentTaskStatus status, String workerId);
    java.util.Optional<DocumentTaskEntity> findByClientRequestIdAndActorUserIdAndWorkspaceId(
            String clientRequestId, String actorUserId, String workspaceId);
    List<DocumentTaskEntity> findByStatusAndLeaseExpiresAtLessThanEqual(DocumentTaskStatus status, Instant expiresAt);
    List<DocumentTaskEntity> findByTypeAndStatusAndFinishedAtLessThanEqual(
            DocumentTaskType type, DocumentTaskStatus status, Instant finishedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentTaskEntity task
               set task.status = :running,
                   task.stage = 'PARSING',
                   task.progress = 15,
                   task.attemptCount = task.attemptCount + 1,
                   task.startedAt = :now,
                   task.nextAttemptAt = null,
                   task.errorMessage = null,
                   task.activeWorkspaceKey = task.workspaceId,
                   task.workerId = :workerId,
                   task.leaseExpiresAt = :leaseExpiresAt
             where task.taskId = :taskId
               and (task.status = :queued or task.status = :retryWait)
               and (task.nextAttemptAt is null or task.nextAttemptAt <= :now)
            """)
    int claim(@Param("taskId") String taskId,
              @Param("queued") DocumentTaskStatus queued,
              @Param("retryWait") DocumentTaskStatus retryWait,
              @Param("running") DocumentTaskStatus running,
              @Param("now") Instant now,
              @Param("workerId") String workerId,
              @Param("leaseExpiresAt") Instant leaseExpiresAt);
}
