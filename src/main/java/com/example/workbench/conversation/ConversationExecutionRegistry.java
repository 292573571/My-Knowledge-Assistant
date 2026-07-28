package com.example.workbench.conversation;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ConversationExecutionRegistry {

    private final ConcurrentHashMap<String, Set<Execution>> executions = new ConcurrentHashMap<>();

    public Execution begin(String conversationScope) {
        Execution execution = new Execution();
        executions.computeIfAbsent(conversationScope, key -> ConcurrentHashMap.newKeySet()).add(execution);
        return execution;
    }

    public void cancel(String conversationScope) {
        executions.getOrDefault(conversationScope, Set.of()).forEach(Execution::cancel);
    }

    public void finish(String conversationScope, Execution execution) {
        executions.computeIfPresent(conversationScope, (key, running) -> {
            running.remove(execution);
            return running.isEmpty() ? null : running;
        });
    }

    public static final class Execution {

        private final String id = UUID.randomUUID().toString();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public boolean isCancelled() {
            return cancelled.get();
        }

        private void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Execution execution && id.equals(execution.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }
}
