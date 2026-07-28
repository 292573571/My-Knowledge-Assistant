package com.example.workbench.eval;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalRunResultRepository extends JpaRepository<EvalRunResultEntity, Long> {
    List<EvalRunResultEntity> findAllByRunIdOrderByIdAsc(Long runId);
}
