package com.example.workbench.rag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.audit.AuditService;
import com.example.workbench.config.AiConfig;
import com.example.workbench.config.GlobalExceptionHandler;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * RagController 的 HTTP 契约：请求体校验、响应 JSON 结构、来源为空时的路由标记。
 *
 * <p>这是全站最主要的问答入口，此前 154 行只覆盖 6 行。这里只钉契约，
 * 不重复 RagService 的内部单元测试。</p>
 */
class RagControllerHttpContractTest {

    private RagService ragService;
    private WorkspaceService workspaceService;
    private ConversationService conversationService;
    private MockMvc mockMvc;
    private AppUser user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ragService = mock(RagService.class);
        workspaceService = mock(WorkspaceService.class);
        conversationService = mock(ConversationService.class);
        user = mock(AppUser.class);

        RagController controller = new RagController(
                mock(DocumentIngestionService.class),
                ragService,
                mock(AiConfig.class),
                mock(ObjectProvider.class),
                mock(ChromaVectorStoreAdapter.class),
                mock(AdminAuthorizationService.class),
                workspaceService,
                mock(AuditService.class),
                mock(DocumentTaskService.class),
                conversationService,
                mock(DocumentVisibilityService.class),
                mock(DocumentHealthService.class));

        when(workspaceService.access(any(), any())).thenReturn(
                new WorkspaceAccessContext("u_alice", "ws-1", WorkspaceRole.OWNER));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("message 为空时返回 400，并透出校验文案")
    void blankMessageReturns400() throws Exception {
        mockMvc.perform(post("/api/rag/chat")
                        .requestAttr(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("message cannot be empty"));
    }

    @Test
    @DisplayName("正常问答返回 answer / sources / route 三段结构")
    void chatReturnsAnswerSourcesAndRoute() throws Exception {
        when(ragService.chat(any(AppUser.class), any(RagChatRequest.class))).thenReturn(new RagChatResponse(
                "Kettle 转换由步骤组成。", List.of(new RagSource("Kettle 手册.pdf", 0, "snippet", 0.9, "第一章"))));

        mockMvc.perform(post("/api/rag/chat")
                        .requestAttr(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c-1\",\"workspaceId\":\"ws-1\",\"message\":\"转换是什么\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Kettle 转换由步骤组成。"))
                .andExpect(jsonPath("$.sources[0].file").value("Kettle 手册.pdf"))
                .andExpect(jsonPath("$.route").value("LOCAL_KNOWLEDGE"));

        verify(ragService).chat(any(AppUser.class), any(RagChatRequest.class));
    }

    @Test
    @DisplayName("无命中文档时 route 标记为 NO_KNOWLEDGE，前端据此展示兜底话术")
    void emptySourcesMarkedAsNoKnowledge() throws Exception {
        when(ragService.chat(any(AppUser.class), any(RagChatRequest.class))).thenReturn(new RagChatResponse("暂无相关内容。", List.of()));

        mockMvc.perform(post("/api/rag/chat")
                        .requestAttr(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"一个没有资料的问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route").value("NO_KNOWLEDGE"))
                .andExpect(jsonPath("$.sources").isEmpty());
    }
}
