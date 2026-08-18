package com.example.workbench.modelconfig;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiModelRepository extends JpaRepository<AiModel, Long> {

    List<AiModel> findAllByOrderByIdAsc();

    Optional<AiModel> findFirstByIsDefaultTrue();

    Optional<AiModel> findFirstByIsDefaultTrueAndEnabledTrue();
}
