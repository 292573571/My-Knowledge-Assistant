package com.example.workbench.agent;

import java.time.Instant;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaMaintenancePendingActionStore implements MaintenancePendingActionStore {

    private final MaintenancePendingActionRepository repository;

    JpaMaintenancePendingActionStore(MaintenancePendingActionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(MaintenancePendingActionState action) {
        repository.save(new MaintenancePendingActionEntity(action));
    }

    @Override
    @Transactional
    public <T> T consume(String token, Function<MaintenancePendingActionState, T> operation) {
        MaintenancePendingActionEntity entity = repository.findByTokenForUpdate(token).orElse(null);
        if (entity == null) return operation.apply(null);
        T result = operation.apply(entity.state());
        repository.delete(entity);
        return result;
    }

    @Override
    @Transactional
    public long deleteExpired(Instant now) {
        return repository.deleteByExpiresAtLessThanEqual(now);
    }
}
