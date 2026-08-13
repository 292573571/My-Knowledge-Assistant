package com.example.workbench.agent;

import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

interface TeachingAttemptRepository extends JpaRepository<TeachingAttemptEntity, String> {

    Optional<TeachingAttemptEntity> findByPracticeId(String practiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from TeachingAttemptEntity attempt where attempt.checkId = :checkId")
    Optional<TeachingAttemptEntity> findByCheckIdForUpdate(@Param("checkId") String checkId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from TeachingAttemptEntity attempt where attempt.practiceId = :practiceId")
    Optional<TeachingAttemptEntity> findByPracticeIdForUpdate(@Param("practiceId") String practiceId);

    Optional<TeachingAttemptEntity> findFirstByOwnerKeyAndWorkspaceIdAndSessionIdOrderByCreatedAtDesc(
            String ownerKey, String workspaceId, String sessionId);

    long countByOwnerKeyAndExpiresAtAfterAndCheckCompletedFalse(String ownerKey, Instant now);

    long countByOwnerKeyAndExpiresAtAfterAndPracticeIdIsNotNullAndPracticeCompletedFalse(String ownerKey, Instant now);

    long deleteByExpiresAtLessThanEqual(Instant now);
}
