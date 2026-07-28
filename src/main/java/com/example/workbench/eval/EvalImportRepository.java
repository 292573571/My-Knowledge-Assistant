package com.example.workbench.eval;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalImportRepository extends JpaRepository<EvalImportEntity, Long> {
    List<EvalImportEntity> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);
    Optional<EvalImportEntity> findByIdAndOwnerId(Long id, Long ownerId);
}
