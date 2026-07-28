package com.example.workbench.eval;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalRunRepository extends JpaRepository<EvalRunEntity, Long> {
    List<EvalRunEntity> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);
    Optional<EvalRunEntity> findByRunIdAndOwnerId(String runId, Long ownerId);
}
