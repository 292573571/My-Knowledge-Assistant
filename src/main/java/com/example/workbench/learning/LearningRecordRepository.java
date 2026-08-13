package com.example.workbench.learning;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LearningRecordRepository extends JpaRepository<LearningRecordEntity, String> {

    @Query("""
            select r from LearningRecordEntity r
             where r.ownerUserId = :userId
               and r.updatedAt is not null
               and (r.workspaceId = :workspaceId or (:includeLegacy = true and r.workspaceId is null))
             order by r.recordDate desc, r.createdAt asc
            """)
    List<LearningRecordEntity> findVisible(@Param("userId") Long userId,
                                            @Param("workspaceId") String workspaceId,
                                            @Param("includeLegacy") boolean includeLegacy);

    @Query("""
            select r from LearningRecordEntity r
             where r.ownerUserId = :userId
               and r.recordDate = :recordDate
               and (r.workspaceId = :workspaceId or (:includeLegacy = true and r.workspaceId is null))
             order by r.createdAt asc
            """)
    List<LearningRecordEntity> findVisibleOnDate(@Param("userId") Long userId,
                                                 @Param("workspaceId") String workspaceId,
                                                 @Param("recordDate") LocalDate recordDate,
                                                 @Param("includeLegacy") boolean includeLegacy);

    Optional<LearningRecordEntity> findFirstBySourceKey(String sourceKey);

    List<LearningRecordEntity> findByOwnerUserIdAndRecordDateOrderByCreatedAtAsc(Long userId, LocalDate recordDate);

    List<LearningRecordEntity> findByOwnerUserIdAndRecordDateAndWorkspaceIdOrderByCreatedAtAsc(
            Long userId, LocalDate recordDate, String workspaceId);

    List<LearningRecordEntity> findByOwnerUserIdAndRecordDateAndWorkspaceIdIsNullOrderByCreatedAtAsc(
            Long userId, LocalDate recordDate);

    @Query("""
            select r from LearningRecordEntity r
             where r.ownerUserId = :userId and r.recordDate = :recordDate
               and ((r.workspaceId = :workspaceId) or (:includeLegacy = true and r.workspaceId is null))
             order by r.createdAt asc
            """)
    List<LearningRecordEntity> findDateForUpdate(@Param("userId") Long userId,
                                                 @Param("workspaceId") String workspaceId,
                                                 @Param("recordDate") LocalDate recordDate,
                                                 @Param("includeLegacy") boolean includeLegacy);

    void deleteByOwnerUserIdAndRecordDateAndWorkspaceId(Long userId, LocalDate recordDate, String workspaceId);
}
