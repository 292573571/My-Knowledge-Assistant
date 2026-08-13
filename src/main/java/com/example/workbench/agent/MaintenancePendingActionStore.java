package com.example.workbench.agent;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

interface MaintenancePendingActionStore {

    void save(MaintenancePendingActionState action);

    <T> T consume(String token, Function<MaintenancePendingActionState, T> operation);

    long deleteExpired(Instant now);
}
