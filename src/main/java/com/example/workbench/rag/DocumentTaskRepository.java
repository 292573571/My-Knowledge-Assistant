package com.example.workbench.rag;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface DocumentTaskRepository extends JpaRepository<DocumentTaskEntity, String> {
    List<DocumentTaskEntity> findTop20ByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    Page<DocumentTaskEntity> findByWorkspaceId(String workspaceId, Pageable pageable);
    Page<DocumentTaskEntity> findByWorkspaceIdAndType(String workspaceId, DocumentTaskType type, Pageable pageable);
    java.util.Optional<DocumentTaskEntity> findFirstBySourcePathAndWorkspaceIdAndTypeOrderByCreatedAtDesc(
            String sourcePath, String workspaceId, DocumentTaskType type);
    java.util.Optional<DocumentTaskEntity> findFirstByDocumentIdAndWorkspaceIdAndTypeOrderByCreatedAtDesc(
            String documentId, String workspaceId, DocumentTaskType type);
    List<DocumentTaskEntity> findByWorkspaceIdAndTypeOrderByCreatedAtDesc(String workspaceId, DocumentTaskType type);
    List<DocumentTaskEntity> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            Collection<DocumentTaskStatus> statuses, Instant nextAttemptAt);
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
                   task.generation = task.generation + 1,
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentTaskEntity task
               set task.leaseExpiresAt = :leaseExpiresAt
             where task.taskId = :taskId
               and task.status = :running
               and task.workerId = :workerId
               and task.generation = :generation
            """)
    int renewLease(@Param("taskId") String taskId, @Param("running") DocumentTaskStatus running,
                   @Param("workerId") String workerId, @Param("generation") long generation,
                   @Param("leaseExpiresAt") Instant leaseExpiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentTaskEntity task
               set task.stage = :stage,
                   task.progress = case when task.progress > :progress then task.progress else :progress end,
                   task.totalItems = :totalItems,
                   task.completedItems = :completedItems,
                   task.succeededItems = :succeededItems,
                   task.failedItems = :failedItems,
                   task.resultChunks = :resultChunks,
                   task.currentBatch = :currentBatch,
                   task.totalBatches = :totalBatches,
                   task.currentStartPage = :currentStartPage,
                   task.currentEndPage = :currentEndPage,
                   task.leaseExpiresAt = :leaseExpiresAt
             where task.taskId = :taskId
               and task.status = :running
               and task.workerId = :workerId
               and task.generation = :generation
            """)
    int updateProgress(@Param("taskId") String taskId, @Param("running") DocumentTaskStatus running,
                       @Param("workerId") String workerId, @Param("generation") long generation,
                       @Param("stage") String stage, @Param("progress") int progress,
                       @Param("totalItems") int totalItems, @Param("completedItems") int completedItems,
                       @Param("succeededItems") int succeededItems, @Param("failedItems") int failedItems,
                       @Param("resultChunks") int resultChunks, @Param("currentBatch") int currentBatch,
                       @Param("totalBatches") int totalBatches, @Param("currentStartPage") int currentStartPage,
                       @Param("currentEndPage") int currentEndPage,
                       @Param("leaseExpiresAt") Instant leaseExpiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentTaskEntity task
               set task.documentId = :documentId,
                   task.status = :succeeded,
                   task.stage = 'DONE',
                   task.progress = 100,
                   task.finishedAt = :finishedAt,
                   task.errorMessage = null,
                   task.retryable = null,
                   task.activeWorkspaceKey = null,
                   task.workerId = null,
                   task.leaseExpiresAt = null
             where task.taskId = :taskId
               and task.status = :running
               and task.workerId = :workerId
               and task.generation = :generation
            """)
    int succeed(@Param("taskId") String taskId, @Param("running") DocumentTaskStatus running,
                @Param("succeeded") DocumentTaskStatus succeeded, @Param("workerId") String workerId,
                @Param("generation") long generation, @Param("documentId") String documentId,
                @Param("finishedAt") Instant finishedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentTaskEntity task
               set task.errorMessage = :errorMessage,
                   task.retryable = :retryable,
                   task.status = :nextStatus,
                   task.stage = :stage,
                   task.nextAttemptAt = :nextAttemptAt,
                   task.finishedAt = :finishedAt,
                   task.activeWorkspaceKey = null,
                   task.workerId = null,
                   task.leaseExpiresAt = null
             where task.taskId = :taskId
               and task.status = :running
               and task.workerId = :workerId
               and task.generation = :generation
            """)
    int fail(@Param("taskId") String taskId, @Param("running") DocumentTaskStatus running,
             @Param("workerId") String workerId, @Param("generation") long generation,
             @Param("errorMessage") String errorMessage, @Param("retryable") boolean retryable,
             @Param("nextStatus") DocumentTaskStatus nextStatus, @Param("stage") String stage,
             @Param("nextAttemptAt") Instant nextAttemptAt, @Param("finishedAt") Instant finishedAt);
}
