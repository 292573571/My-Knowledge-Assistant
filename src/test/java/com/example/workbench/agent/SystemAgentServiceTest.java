package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AdminUserResponse;
import com.example.workbench.auth.AdminUserService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AppUserRepository;
import com.example.workbench.auth.SystemRole;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.audit.AuditAction;
import com.example.workbench.audit.AuditOutcome;
import com.example.workbench.audit.AuditService;
import com.example.workbench.logview.SystemLogRepository;
import com.example.workbench.modelconfig.AiModelResponse;
import com.example.workbench.modelconfig.AiModelService;
import com.example.workbench.modelconfig.AiModelType;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.DocumentTaskService;
import com.example.workbench.rag.DocumentTaskType;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.workspace.WorkspaceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.server.ResponseStatusException;

class SystemAgentServiceTest {

    private DocumentTaskService taskService;
    private AiModelService aiModelService;
    private AdminUserService adminUserService;
    private AppUserRepository appUserRepository;
    private SystemLogRepository systemLogRepository;
    private AuditService auditService;
    private WorkspaceService workspaceService;
    private SystemAgentService service;
    private final AdminAuthorizationService adminAuthorization = new AdminAuthorizationService("");

    private final AppUser superAdmin = new AppUser("admin", "Admin", "hash");
    private final AppUser plainAdmin = admin("ops");
    private final AppUser normalUser = new AppUser("alice", "Alice", "hash");

    @BeforeEach
    void setUp() {
        taskService = mock(DocumentTaskService.class);
        aiModelService = mock(AiModelService.class);
        adminUserService = mock(AdminUserService.class);
        appUserRepository = mock(AppUserRepository.class);
        systemLogRepository = mock(SystemLogRepository.class);
        auditService = mock(AuditService.class);
        workspaceService = mock(WorkspaceService.class);
        service = new SystemAgentService(mock(ChatClient.class), mock(MaintenanceReadOnlyService.class),
                mock(SystemReadOnlyService.class), taskService, mock(DocumentIngestionService.class),
                aiModelService, adminUserService, appUserRepository, systemLogRepository, auditService,
                adminAuthorization, workspaceService, new InMemoryMaintenancePendingActionStore());
    }

    @Test
    void rejectsDefaultModelChangeForPlainAdmin() {
        assertThatThrownBy(() -> service.chat(plainAdmin, context(plainAdmin), "把模型 3 设为默认模型"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("超级管理员");
        verifyNoInteractions(aiModelService);
    }

    @Test
    void answersGreetingWithoutCallingSystemTools() {
        MaintenanceAgentResult result = service.chat(plainAdmin, context(plainAdmin), "你好！");

        assertThat(result.answer()).startsWith("你好，我是识海系统管家");
        assertThat(result.traces()).isEmpty();
        assertThat(result.steps()).isEqualTo(1);
        verifyNoInteractions(taskService, aiModelService, adminUserService, appUserRepository,
                systemLogRepository, workspaceService);
    }

    @Test
    void explainsCapabilitiesWithoutCallingSystemTools() {
        MaintenanceAgentResult result = service.chat(plainAdmin, context(plainAdmin), "你能做什么？");

        assertThat(result.answer()).contains("系统管家", "查询系统运行状态", "所有写操作都会先让你确认");
        assertThat(result.traces()).isEmpty();
        verifyNoInteractions(taskService, aiModelService, adminUserService, appUserRepository,
                systemLogRepository, workspaceService);
    }

    @Test
    void proposesDefaultModelChangeForSuperAdmin() {
        MaintenanceAgentResult result = service.chat(superAdmin, context(superAdmin), "把模型 3 设为默认模型");

        assertThat(result.pendingAction()).isNotNull();
        assertThat(result.pendingAction().action()).isEqualTo(MaintenanceAction.SET_DEFAULT_MODEL);
        assertThat(result.pendingAction().targetId()).isEqualTo("3");
    }

    @Test
    void confirmAppliesDefaultModelChangeAndWritesAudit() {
        WorkspaceAccessContext workspaceContext = context(superAdmin);
        when(aiModelService.setDefault(eq(superAdmin), eq(3L))).thenReturn(new AiModelResponse(
                3L, "DeepSeek V3", AiModelType.CHAT, "https://api.example.com", "sk-****", "deepseek-v3",
                null, null, null, null, null, true, true, null));

        MaintenanceAgentResult proposal = service.chat(superAdmin, workspaceContext, "把模型 3 设为默认模型");
        MaintenanceWriteResult write = service.confirm(superAdmin, workspaceContext,
                proposal.pendingAction().confirmationToken());

        assertThat(write.answer()).contains("DeepSeek V3");
        verify(aiModelService).setDefault(superAdmin, 3L);
        verify(auditService).record(eq(superAdmin), any(), eq(AuditAction.MODEL_CONFIG_DEFAULT_CHANGE),
                any(), any(), eq(AuditOutcome.SUCCESS), any(), any());
    }

    @Test
    void proposesUserRoleChangeWithTargetRolePayload() {
        MaintenanceAgentResult result = service.chat(superAdmin, context(superAdmin), "把用户 alice 设为管理员");

        assertThat(result.pendingAction()).isNotNull();
        assertThat(result.pendingAction().action()).isEqualTo(MaintenanceAction.SET_USER_ROLE);
        assertThat(result.pendingAction().targetId()).isEqualTo("alice");
        assertThat(result.pendingAction().payload()).isEqualTo("ADMIN");
    }

    @Test
    void rejectsUserRoleChangeForPlainAdmin() {
        assertThatThrownBy(() -> service.chat(plainAdmin, context(plainAdmin), "把用户 alice 设为管理员"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("超级管理员");
    }

    @Test
    void confirmAppliesUserRoleChange() {
        AppUser target = new AppUser("alice", "Alice", "hash");
        target.initializeProfile("usr_alice", null);
        when(appUserRepository.findByAccount("alice")).thenReturn(Optional.of(target));
        when(adminUserService.changeRole(eq(superAdmin), any(), any())).thenReturn(new AdminUserResponse(
                "usr_alice", "alice", null, null, "Alice", SystemRole.ADMIN, Instant.now()));

        WorkspaceAccessContext workspaceContext = context(superAdmin);
        MaintenanceAgentResult proposal = service.chat(superAdmin, workspaceContext, "把用户 alice 设为管理员");
        MaintenanceWriteResult write = service.confirm(superAdmin, workspaceContext,
                proposal.pendingAction().confirmationToken());

        assertThat(write.answer()).contains("alice").contains("ADMIN");
        verify(adminUserService).changeRole(eq(superAdmin), eq("usr_alice"), any());
        verify(auditService).record(eq(superAdmin), any(), eq(AuditAction.USER_ROLE_CHANGE),
                any(), eq("usr_alice"), eq(AuditOutcome.SUCCESS), any(), any());
    }

    @Test
    void clearSystemLogsRequiresConfirmationAndWritesAudit() {
        when(systemLogRepository.count()).thenReturn(42L);
        WorkspaceAccessContext workspaceContext = context(plainAdmin);

        MaintenanceAgentResult proposal = service.chat(plainAdmin, workspaceContext, "清理系统日志");
        assertThat(proposal.pendingAction()).isNotNull();
        assertThat(proposal.pendingAction().action()).isEqualTo(MaintenanceAction.CLEAR_SYSTEM_LOGS);

        MaintenanceWriteResult write = service.confirm(plainAdmin, workspaceContext,
                proposal.pendingAction().confirmationToken());

        assertThat(write.answer()).contains("42");
        verify(systemLogRepository).deleteAll();
        verify(auditService).record(eq(plainAdmin), any(), eq(AuditAction.SYSTEM_LOG_CLEAR),
                any(), any(), eq(AuditOutcome.SUCCESS), any(), any());
    }

    @Test
    void confirmRejectsPrivilegeRevokedAfterProposal() {
        AppUser promoted = new AppUser("root", "Root", "hash");
        promoted.changeSystemRole(SystemRole.SUPER_ADMIN);
        WorkspaceAccessContext workspaceContext = context(promoted);

        MaintenanceAgentResult proposal = service.chat(promoted, workspaceContext, "把模型 5 设为默认模型");
        promoted.changeSystemRole(SystemRole.USER);

        assertThatThrownBy(() -> service.confirm(promoted, workspaceContext,
                proposal.pendingAction().confirmationToken()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("超级管理员");
        verifyNoInteractions(aiModelService);
    }

    @Test
    void confirmSingleWorkspaceActionRejectsRevokedAdmin() {
        AppUser promoted = admin("ops-2");
        WorkspaceAccessContext workspaceContext = context(promoted);
        MaintenanceAgentResult proposal = service.chat(promoted, workspaceContext, "重建索引");
        promoted.changeSystemRole(SystemRole.USER);

        assertThatThrownBy(() -> service.confirm(promoted, workspaceContext,
                proposal.pendingAction().confirmationToken()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("系统管理员");
        verifyNoInteractions(taskService);
    }

    @Test
    void keepsRebuildAllIntentForSuperAdmin() {
        when(workspaceService.allWorkspaceAccesses(superAdmin)).thenReturn(List.of(context(superAdmin)));

        MaintenanceAgentResult result = service.chat(superAdmin, context(superAdmin), "重建所有向量索引");

        assertThat(result.pendingAction()).isNotNull();
        assertThat(result.pendingAction().action()).isEqualTo(MaintenanceAction.REBUILD_ALL_INDEX);
    }

    @Test
    void confirmRebuildAllReturnsTaskReferencesForProgressTracking() {
        WorkspaceAccessContext first = context(superAdmin);
        WorkspaceAccessContext second = new WorkspaceAccessContext("admin", "team-2",
                WorkspaceRole.OWNER, WorkspaceType.TEAM);
        when(workspaceService.allWorkspaceAccesses(superAdmin)).thenReturn(List.of(first, second));
        when(taskService.createMaintenance(first, DocumentTaskType.REBUILD, null))
                .thenReturn(task("task-1", first.workspaceId()));
        when(taskService.createMaintenance(second, DocumentTaskType.REBUILD, null))
                .thenReturn(task("task-2", second.workspaceId()));

        MaintenanceAgentResult proposal = service.chat(superAdmin, first, "重建所有向量索引");
        MaintenanceWriteResult result = service.confirm(superAdmin, first,
                proposal.pendingAction().confirmationToken());

        assertThat(result.tasks()).containsExactly(
                new MaintenanceTaskReference("task-1", first.workspaceId()),
                new MaintenanceTaskReference("task-2", second.workspaceId()));
    }

    @Test
    void nonAdminCannotUseSystemReadOnlyService() {
        SystemReadOnlyService readOnly = new SystemReadOnlyService(mock(MaintenanceReadOnlyService.class),
                mock(AdminUserService.class), mock(AiModelService.class), mock(AuditService.class),
                mock(SystemLogRepository.class), workspaceService,
                mock(com.example.workbench.eval.EvalRunStorage.class), adminAuthorization);

        assertThatThrownBy(() -> readOnly.overview(new MaintenanceAgentContext(normalUser, context(normalUser))))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static AppUser admin(String account) {
        AppUser user = new AppUser(account, "Ops", "hash");
        user.changeSystemRole(SystemRole.ADMIN);
        return user;
    }

    private static WorkspaceAccessContext context(AppUser user) {
        return new WorkspaceAccessContext(UserConversationScope.ownerId(user), "personal-1",
                WorkspaceRole.OWNER, WorkspaceType.PERSONAL);
    }

    private static com.example.workbench.rag.DocumentTaskResponse task(String taskId, String workspaceId) {
        return new com.example.workbench.rag.DocumentTaskResponse(taskId, DocumentTaskType.REBUILD,
                com.example.workbench.rag.DocumentTaskStatus.QUEUED, "QUEUED", 5, workspaceId,
                "重建空间索引", null, 0, 3, null, true, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Instant.now(), null, null, false);
    }
}
