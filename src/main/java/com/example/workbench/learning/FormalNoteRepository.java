package com.example.workbench.learning;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface FormalNoteRepository extends JpaRepository<FormalNoteEntity, String> {
    Optional<FormalNoteEntity> findByOwnerUserIdAndWorkspaceIdAndNoteDate(Long ownerUserId, String workspaceId,
                                                                            LocalDate noteDate);
}
