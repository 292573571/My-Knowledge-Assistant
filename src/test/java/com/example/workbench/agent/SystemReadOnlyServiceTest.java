package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AdminUserResponse;
import com.example.workbench.auth.AdminUserService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.SystemRole;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.audit.AuditService;
import com.example.workbench.eval.EvalRunStorage;
import com.example.workbench.logview.SystemLog;
import com.example.workbench.logview.SystemLogRepository;
import com.example.workbench.modelconfig.AiModelService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.workspace.WorkspaceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

class SystemReadOnlyServiceTest {

    private AdminUserService adminUserService;
    private SystemLogRepository systemLogRepository;
    private SystemReadOnlyService service;
    private final AdminAuthorizationService adminAuthorization = new AdminAuthorizationService("");

    private final AppUser superAdmin = new AppUser("admin", "Admin", "hash");
    private final AppUser normalUser = new AppUser("alice", "Alice", "hash");

    @BeforeEach
    void setUp() {
        adminUserService = mock(AdminUserService.class);
        systemLogRepository = mock(SystemLogRepository.class);
        service = new SystemReadOnlyService(mock(MaintenanceReadOnlyService.class), adminUserService,
                mock(AiModelService.class), mock(AuditService.class), systemLogRepository,
                mock(WorkspaceService.class), mock(EvalRunStorage.class), adminAuthorization);
    }

    @Test
    void rejectsNonAdminCallers() {
        assertThatThrownBy(() -> service.users(context(normalUser), "", 10))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.systemLogs(context(normalUser), "ERROR", 10))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void masksUserEmail() {
        when(adminUserService.list(superAdmin)).thenReturn(List.of(new AdminUserResponse(
                "usr_1", "alice@example.com", "alice@example.com", "13800000000", "Alice",
                SystemRole.USER, Instant.now())));

        SystemReadOnlyService.UserDirectory directory = service.users(context(superAdmin), "", 10);

        assertThat(directory.users()).singleElement().satisfies(user -> {
            assertThat(user.maskedEmail()).isEqualTo("a***@example.com");
            assertThat(user.account()).isEqualTo("alice@example.com");
        });
    }

    @Test
    void redactsSecretsFromSystemLogs() {
        when(systemLogRepository.findAllByOrderByTimestampDesc(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(new SystemLog(Instant.now(), "ERROR", "com.example.Foo",
                        "http-nio-8080-exec-1",
                        "call failed apiKey=sk-abcdef123456 and token=abcdefghijklmnop"))));

        SystemReadOnlyService.LogDirectory directory = service.systemLogs(context(superAdmin), null, 10);

        assertThat(directory.logs()).singleElement().satisfies(log -> {
            assertThat(log.messageExcerpt()).doesNotContain("sk-abcdef123456");
            assertThat(log.messageExcerpt()).contains("[REDACTED]");
        });
    }

    @Test
    void boundsRequestedLimit() {
        when(adminUserService.list(superAdmin)).thenReturn(List.of(new AdminUserResponse(
                "usr_1", "alice", null, null, "Alice", SystemRole.USER, Instant.now())));

        // 超过上限时只按 MAX_LIMIT(50) 截断，且不抛异常。
        assertThat(service.users(context(superAdmin), "", 999).users()).hasSize(1);
    }

    private static MaintenanceAgentContext context(AppUser user) {
        return new MaintenanceAgentContext(user, new WorkspaceAccessContext(
                UserConversationScope.ownerId(user), "personal-1", WorkspaceRole.OWNER, WorkspaceType.PERSONAL));
    }
}
