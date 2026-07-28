package com.example.workbench.eval;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalCaseRepository extends JpaRepository<EvalCaseEntity, Long> {

    List<EvalCaseEntity> findAllByOwnerIdOrderByIdAsc(Long ownerId);

    Optional<EvalCaseEntity> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerId(Long ownerId);
}
