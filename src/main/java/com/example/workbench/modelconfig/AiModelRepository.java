package com.example.workbench.modelconfig;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiModelRepository extends JpaRepository<AiModel, Long> {

    List<AiModel> findAllByOrderByIdAsc();

    Optional<AiModel> findFirstByIsDefaultTrueAndModelTypeAndEnabledTrue(AiModelType modelType);

    Optional<AiModel> findFirstByIsDefaultTrueAndModelType(AiModelType modelType);

    List<AiModel> findByModelType(AiModelType modelType);
}
