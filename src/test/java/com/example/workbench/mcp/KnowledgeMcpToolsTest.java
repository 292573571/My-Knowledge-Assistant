package com.example.workbench.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.rag.DocumentContentResponse;
import com.example.workbench.rag.DocumentIndexEntry;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagSource;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class KnowledgeMcpToolsTest {

    private RagService ragService;
    private DocumentIngestionService ingestionService;
    private WorkspaceService workspaceService;
    private KnowledgeMcpTools tools;
    private final AppUser user = new AppUser("alice", "Alice", "hash");

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        ingestionService = mock(DocumentIngestionService.class);
        workspaceService = mock(WorkspaceService.class);
        tools = new KnowledgeMcpTools(ragService, ingestionService, workspaceService);
        McpRequestContext.clear();
    }

    @AfterEach
    void tearDown() {
        McpRequestContext.clear();
    }

    @Test
    void searchScopesRetrievalToCurrentUser() {
        McpRequestContext.set(user);
        WorkspaceAccessContext access = new WorkspaceAccessContext(UserConversationScope.ownerId(user),
                "personal-1", WorkspaceRole.OWNER);
        when(workspaceService.access(user, "personal-1")).thenReturn(access);
        when(workspaceService.effectiveReadableWorkspaceIds(user, "personal-1"))
                .thenReturn(Set.of("personal-1"));
        when(ragService.retrieveForAgent(anyString(), anyString(), ArgumentMatchers.<Set<String>>any(), anyInt()))
                .thenReturn(List.of(new RagSource("手册.pdf", 2, "季度报销流程", 0.87, "第三章 > 报销", "/docs/手册.pdf", null)));

        List<KnowledgeMcpTools.KnowledgeHit> hits = tools.searchKnowledge("报销流程", "personal-1", 3);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).file()).isEqualTo("手册.pdf");
        assertThat(hits.get(0).snippet()).contains("报销");
        org.mockito.Mockito.verify(ragService).retrieveForAgent(eq("报销流程"),
                eq(UserConversationScope.ownerId(user)), eq(Set.of("personal-1")), eq(3));
    }

    @Test
    void searchClampsLimitIntoSupportedRange() {
        McpRequestContext.set(user);
        WorkspaceAccessContext access = new WorkspaceAccessContext(UserConversationScope.ownerId(user),
                "personal-1", WorkspaceRole.OWNER);
        when(workspaceService.access(user, "personal-1")).thenReturn(access);
        when(workspaceService.effectiveReadableWorkspaceIds(user, "personal-1"))
                .thenReturn(Set.of("personal-1"));
        when(ragService.retrieveForAgent(anyString(), anyString(), ArgumentMatchers.<Set<String>>any(), anyInt()))
                .thenReturn(List.of());

        tools.searchKnowledge("任意", "personal-1", 99);

        org.mockito.Mockito.verify(ragService).retrieveForAgent("任意", UserConversationScope.ownerId(user),
                Set.of("personal-1"), 10);
    }

    @Test
    void rejectsUnauthenticatedToolInvocation() {
        assertThatThrownBy(() -> tools.searchKnowledge("报销", null, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("认证");
    }

    @Test
    void listsDocumentsThroughWorkspaceAccess() {
        McpRequestContext.set(user);
        WorkspaceAccessContext access = new WorkspaceAccessContext(UserConversationScope.ownerId(user),
                "personal-1", WorkspaceRole.OWNER);
        when(workspaceService.access(user, "personal-1")).thenReturn(access);
        when(ingestionService.listWorkspaceIndexedDocuments(access)).thenReturn(List.of(
                new DocumentIndexEntry("doc-1", "手册.pdf", "/docs/手册.pdf", "hash", 12, Instant.now())));

        List<KnowledgeMcpTools.DocumentSummary> documents = tools.listDocuments("personal-1");

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).documentId()).isEqualTo("doc-1");
        assertThat(documents.get(0).fileName()).isEqualTo("手册.pdf");
        assertThat(documents.get(0).chunkCount()).isEqualTo(12);
    }

    @Test
    void readsDocumentContentThroughWorkspaceAccess() {
        McpRequestContext.set(user);
        WorkspaceAccessContext access = new WorkspaceAccessContext(UserConversationScope.ownerId(user),
                "personal-1", WorkspaceRole.OWNER);
        when(workspaceService.access(user, null)).thenReturn(access);
        when(ingestionService.documentContent("doc-1", access)).thenReturn(
                new DocumentContentResponse("doc-1", "手册.pdf", "/docs/手册.pdf", "SOURCE", "正文", true));

        DocumentContentResponse content = tools.getDocument("doc-1", null);

        assertThat(content.documentId()).isEqualTo("doc-1");
        assertThat(content.content()).isEqualTo("正文");
        assertThat(content.sourceAvailable()).isTrue();
    }

    @Test
    void doesNotLeakOtherUsersDocuments() {
        AppUser other = new AppUser("mallory", "Mallory", "hash");
        McpRequestContext.set(other);
        WorkspaceAccessContext access = new WorkspaceAccessContext(UserConversationScope.ownerId(other),
                "personal-2", WorkspaceRole.OWNER);
        when(workspaceService.access(eq(other), ArgumentMatchers.<String>any())).thenReturn(access);
        when(ingestionService.listWorkspaceIndexedDocuments(access)).thenReturn(List.of());

        assertThat(tools.listDocuments(null)).isEmpty();
        org.mockito.Mockito.verify(workspaceService).access(other, null);
        org.mockito.Mockito.verify(ingestionService).listWorkspaceIndexedDocuments(access);
    }

    @Test
    void unusedMocksAreQuiet() {
        assertThat(tools).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(ragService, ingestionService, workspaceService);
    }

    @Test
    void searchRejectsWorkspaceTheCallerCannotAccess() {
        McpRequestContext.set(user);
        when(workspaceService.access(user, "team-9"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "空间不存在"));

        // 回归锁：MCP 客户端传入的 workspaceId 必须先过成员校验，不能越权检索他人空间。
        assertThatThrownBy(() -> tools.searchKnowledge("问题", "team-9", null))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verify(ragService, org.mockito.Mockito.never())
                .retrieveForAgent(anyString(), anyString(), ArgumentMatchers.<Set<String>>any(), anyInt());
    }

    @Test
    void searchExpandsReadableWorkspacesFromAuthorizedContext() {
        McpRequestContext.set(user);
        WorkspaceAccessContext access = new WorkspaceAccessContext(UserConversationScope.ownerId(user),
                "org-1", WorkspaceRole.OWNER);
        when(workspaceService.access(user, "org-1")).thenReturn(access);
        when(workspaceService.effectiveReadableWorkspaceIds(user, "org-1"))
                .thenReturn(Set.of("org-1", "team-1", "team-2"));
        when(ragService.retrieveForAgent(anyString(), anyString(), ArgumentMatchers.<Set<String>>any(), anyInt()))
                .thenReturn(List.of());

        tools.searchKnowledge("组织制度", "org-1", 5);

        org.mockito.Mockito.verify(ragService).retrieveForAgent("组织制度",
                UserConversationScope.ownerId(user), Set.of("org-1", "team-1", "team-2"), 5);
    }

    @Test
    void searchHandlesEmptyRetrievalResult() {
        McpRequestContext.set(user);
        WorkspaceAccessContext access = new WorkspaceAccessContext(UserConversationScope.ownerId(user),
                "personal-1", WorkspaceRole.OWNER);
        when(workspaceService.access(user, null)).thenReturn(access);
        when(workspaceService.effectiveReadableWorkspaceIds(user, "personal-1"))
                .thenReturn(Set.of("personal-1"));
        when(ragService.retrieveForAgent(anyString(), anyString(), ArgumentMatchers.<Set<String>>any(), anyInt()))
                .thenReturn(List.of());

        assertThat(tools.searchKnowledge("无命中", null, null)).isEmpty();
        org.mockito.Mockito.verify(ragService).retrieveForAgent(eq("无命中"),
                eq(UserConversationScope.ownerId(user)), eq(Set.of("personal-1")), eq(5));
    }

    @Test
    void getDocumentRejectsUnauthenticatedCaller() {
        assertThatThrownBy(() -> tools.getDocument("doc-1", null))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(workspaceService, ingestionService);
    }

    @Test
    void listDocumentsRejectsUnauthenticatedCaller() {
        assertThatThrownBy(() -> tools.listDocuments(null))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(workspaceService, ingestionService);
    }

    @Test
    void contextIsClearedBetweenRequests() {
        McpRequestContext.set(user);
        McpRequestContext.clear();

        assertThat(McpRequestContext.currentUser()).isNull();
        assertThatThrownBy(() -> tools.searchKnowledge("x", null, 1)).isInstanceOf(IllegalStateException.class);
    }
}
