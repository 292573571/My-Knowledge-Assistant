package com.example.workbench.agent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class InMemoryMaintenancePendingActionStore implements MaintenancePendingActionStore {

    private final Map<String, MaintenancePendingActionState> actions = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public void save(MaintenancePendingActionState action) {
        actions.put(action.token, action);
    }

    @Override
    public <T> T consume(String token, Function<MaintenancePendingActionState, T> operation) {
        Object lock = locks.computeIfAbsent(token, ignored -> new Object());
        synchronized (lock) {
            MaintenancePendingActionState action = actions.get(token);
            T result = operation.apply(action);
            if (action != null) actions.remove(token, action);
            return result;
        }
    }

    @Override
    public long deleteExpired(Instant now) {
        long before = actions.size();
        actions.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
        return before - actions.size();
    }
}
