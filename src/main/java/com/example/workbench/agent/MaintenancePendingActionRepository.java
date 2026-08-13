package com.example.workbench.agent;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MaintenancePendingActionRepository extends JpaRepository<MaintenancePendingActionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select action from MaintenancePendingActionEntity action where action.confirmationToken = :token")
    Optional<MaintenancePendingActionEntity> findByTokenForUpdate(@Param("token") String token);

    long deleteByExpiresAtLessThanEqual(Instant now);
}
