package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MaintenancePendingActionStoreTest {

    @Test
    void consumesAConfirmationOnlyOnce() {
        InMemoryMaintenancePendingActionStore store = new InMemoryMaintenancePendingActionStore();
        MaintenancePendingActionState action = action("token-1", "workspace-a");
        store.save(action);

        String first = store.consume("token-1", state -> state == null ? "missing" : state.description);
        String second = store.consume("token-1", state -> state == null ? "missing" : state.description);

        assertThat(first).isEqualTo("确认删除文档");
        assertThat(second).isEqualTo("missing");
    }

    @Test
    void keepsAConfirmationWhenExecutionFails() {
        InMemoryMaintenancePendingActionStore store = new InMemoryMaintenancePendingActionStore();
        store.save(action("token-1", "workspace-a"));

        assertThatThrownBy(() -> store.consume("token-1", state -> {
            throw new IllegalStateException("执行失败");
        })).isInstanceOf(IllegalStateException.class);

        String workspaceId = store.consume("token-1", state -> state == null ? "missing" : state.workspaceId);
        assertThat(workspaceId).isEqualTo("workspace-a");
    }

    @Test
    void removesOnlyExpiredConfirmations() {
        InMemoryMaintenancePendingActionStore store = new InMemoryMaintenancePendingActionStore();
        Instant now = Instant.parse("2026-08-13T10:00:00Z");
        store.save(new MaintenancePendingActionState("expired", "alice", "workspace-a",
                MaintenanceAction.DELETE_DOCUMENT, "doc-a", "过期", now.minusSeconds(1)));
        store.save(new MaintenancePendingActionState("active", "alice", "workspace-b",
                MaintenanceAction.REBUILD_INDEX, "", "有效", now.plusSeconds(60)));

        assertThat(store.deleteExpired(now)).isEqualTo(1);
        MaintenancePendingActionState expired = store.consume("expired", state -> state);
        assertThat(expired).isNull();
        String activeWorkspaceId = store.consume("active", state -> state.workspaceId);
        assertThat(activeWorkspaceId).isEqualTo("workspace-b");
    }

    @Test
    void jpaStoreLocksAndDeletesTheConsumedEntity() {
        MaintenancePendingActionRepository repository = mock(MaintenancePendingActionRepository.class);
        JpaMaintenancePendingActionStore store = new JpaMaintenancePendingActionStore(repository);
        MaintenancePendingActionEntity entity = new MaintenancePendingActionEntity(action("token-1", "workspace-a"));
        when(repository.findByTokenForUpdate("token-1")).thenReturn(Optional.of(entity));

        String result = store.consume("token-1", state -> state.action.name());

        assertThat(result).isEqualTo("DELETE_DOCUMENT");
        verify(repository).findByTokenForUpdate("token-1");
        verify(repository).delete(entity);
    }

    private MaintenancePendingActionState action(String token, String workspaceId) {
        return new MaintenancePendingActionState(token, "alice", workspaceId,
                MaintenanceAction.DELETE_DOCUMENT, "doc-1", "确认删除文档",
                Instant.parse("2026-08-13T10:10:00Z"));
    }
}
