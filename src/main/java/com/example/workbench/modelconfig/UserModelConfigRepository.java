package com.example.workbench.modelconfig;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserModelConfigRepository extends JpaRepository<UserModelConfig, Long> {

    Optional<UserModelConfig> findByUserId(Long userId);
}
