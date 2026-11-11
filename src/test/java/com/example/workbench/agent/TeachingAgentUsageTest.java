package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.modelconfig.ModelClientFactory;
import com.example.workbench.modelconfig.ModelConfigContext;
import com.example.workbench.modelconfig.ModelConfigService;
import com.example.workbench.modelconfig.ModelSpec;
import com.example.workbench.observability.ModelUsageRecorder;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * 教学链路的用量采集回归测试。
 *
 * <p>教学 Agent 不走 LocalChatClient，用量必须在 TeachingAgentService 自己的出口记录，
 * 否则 usage 报表长期只覆盖 RAG，教学成本与失败率不可见。
 */
class TeachingAgentUsageTest {

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final TeachingReadOnlyService readOnlyService = mock(TeachingReadOnlyService.class);
    private final TeachingCheckService checkService = mock(TeachingCheckService.class);
    private final LearningRecordService learningRecordService = mock(LearningRecordService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final ModelUsageRecorder usageRecorder = mock(ModelUsageRecorder.class);
    private final ModelConfigContext modelConfigContext = mock(ModelConfigContext.class);
    private final ModelConfigService modelConfigService = mock(ModelConfigService.class);
    private final ModelClientFactory modelClientFactory = mock(ModelClientFactory.class);

    private final AppUser user = new AppUser("alice", "Alice", "hash");
    private final WorkspaceAccessContext access =
            new WorkspaceAccessContext("user-1", "workspace-a", WorkspaceRole.VIEWER);
    private final ModelSpec spec = new ModelSpec(
            "pool", "https://api.example.com", "key", "deepseek-teaching", 0.7, null, null, 1000L, null);

    private TeachingAgentService service() {
        TeachingAgentService service = new TeachingAgentService(chatClient, readOnlyService, checkService,
                learningRecordService, new ObjectMapper(), workspaceService,
                modelConfigContext, modelConfigService, modelClientFactory, null);
        service.setUsageRecorder(usageRecorder);
        return service;
    }

    @Test
    @DisplayName("流式讲解结束后按真实 usage 记录 teaching-stream")
    void recordsStreamingUsage() {
        when(modelConfigService.resolve(any())).thenReturn(spec);
        when(modelClientFactory.clientFor(spec)).thenReturn(chatClient);
        ChatResponse streaming = response("讲解正文", 11, 7);
        ChatResponse check = response("{\"topic\":\"Agent\",\"checkQuestion\":\"什么是 Agent？\"}", 5, 3);
        when(chatClient.prompt().system(anyString()).messages(anyList()).user(anyString())
                .tools(any(Object[].class)).stream().chatResponse())
                .thenReturn(Flux.just(streaming));
        when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
                .thenReturn(check);
        when(checkService.createPending(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new TeachingCheckPrompt("check-1", "什么是 Agent？"));

        TeachingAgentResult result = service().streamChat(user, access,
                new TeachingAgentRequest("workspace-a", "lesson-1", "Agent", TeachingUserLevel.BEGINNER, "请解释 Agent"),
                token -> { }, () -> false);

        assertThat(result.answer()).isEqualTo("讲解正文");
        verify(usageRecorder).record(eq(null), eq("lesson-1"), eq("deepseek-teaching"), eq("teaching-stream"),
                eq(11), eq(7), eq(18), anyLong(), eq(true));
        verify(usageRecorder).record(eq(null), eq("lesson-1"), eq("deepseek-teaching"), eq("teaching-check"),
                eq(5), eq(3), eq(8), anyLong(), eq(true));
    }

    @Test
    @DisplayName("模型调用失败也落一条 success=false，失败率才可统计")
    void recordsFailure() {
        when(modelConfigService.resolve(any())).thenReturn(spec);
        when(modelClientFactory.clientFor(spec)).thenReturn(chatClient);
        when(chatClient.prompt().system(anyString()).messages(anyList()).user(anyString())
                .tools(any(Object[].class)).stream().chatResponse())
                .thenThrow(new IllegalStateException("provider down"));

        TeachingAgentResult result = service().streamChat(user, access,
                new TeachingAgentRequest("workspace-a", "lesson-1", "Agent", TeachingUserLevel.BEGINNER, "请解释 Agent"),
                token -> { }, () -> false);

        assertThat(result.answer()).contains("未能完成");
        verify(usageRecorder).record(eq(null), eq("lesson-1"), eq("deepseek-teaching"), eq("teaching-stream"),
                eq(null), eq(null), eq(null), anyLong(), eq(false));
    }

    private ChatResponse response(String text, int promptTokens, int completionTokens) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        when(usage.getTotalTokens()).thenReturn(promptTokens + completionTokens);
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(usage).build());
    }
}
