package com.example.workbench.mcp;

import com.example.workbench.auth.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpApiKeyRepository extends JpaRepository<McpApiKeyEntity, Long> {

    Optional<McpApiKeyEntity> findByKeyHash(String keyHash);

    List<McpApiKeyEntity> findAllByUserOrderByCreatedAtDesc(AppUser user);
}
