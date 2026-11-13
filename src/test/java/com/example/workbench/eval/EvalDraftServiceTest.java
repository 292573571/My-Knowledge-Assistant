package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.KnowledgeChunkSampler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class EvalDraftServiceTest {

    private final KnowledgeChunkSampler sampler = mock(KnowledgeChunkSampler.class);
    private final EvalCaseService evalCaseService = mock(EvalCaseService.class);
    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatClient> chatClientProvider = mock(ObjectProvider.class);
    private final EvalDraftService service =
            new EvalDraftService(sampler, evalCaseService, chatClientProvider, new ObjectMapper());

    private final AppUser admin = new AppUser("admin", "管理员", "hash");

    private KnowledgeChunkSampler.ChunkSample sample() {
        return new KnowledgeChunkSampler.ChunkSample("doc-1", "deploy-guide.md", "发布流程",
                "发布前必须先在服务器执行 deploy-shihai --yes，然后校验 REVISION 文件是否为完整 commit。");
    }

    @Test
    void generatesDraftFromRealChunk() {
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(sampler.sample(3)).thenReturn(List.of(sample()));
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("```json\n{\"question\":\"发布前要执行什么命令？\",\"keywords\":[\"deploy-shihai\",\"REVISION\"]}\n```");
        when(evalCaseService.createDraft(any(), anyString(), any(), any()))
                .thenReturn(new EvalCaseResponse(1L, "draft-001", "rag", "fact", "发布前要执行什么命令？",
                        false, true, false, List.of("deploy-guide.md"), List.of(), List.of("deploy-shihai"),
                        List.of(), List.of(), List.of(), List.of(), EvalSuite.DRAFT, EvalLayer.RETRIEVAL,
                        List.of(), null, null, List.of(), null));

        EvalDraftService.DraftGenerationResponse response = service.generate(admin, 1);

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.caseIds()).containsExactly("draft-001");
        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> sources = ArgumentCaptor.forClass(List.class);
        verify(evalCaseService).createDraft(eq(admin), question.capture(), sources.capture(), any());
        assertThat(question.getValue()).isEqualTo("发布前要执行什么命令？");
        assertThat(sources.getValue()).containsExactly("deploy-guide.md");
    }

    @Test
    void skipsDraftWhoseKeywordsAreNotInChunk() {
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(sampler.sample(3)).thenReturn(List.of(sample()));
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("{\"question\":\"完全无关的问题？\",\"keywords\":[\"kubernetes\",\"helm\"]}");

        EvalDraftService.DraftGenerationResponse response = service.generate(admin, 1);

        assertThat(response.created()).isZero();
        verify(evalCaseService, never()).createDraft(any(), anyString(), any(), any());
    }

    @Test
    void rejectsWhenNoChunksAvailable() {
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClient);
        when(sampler.sample(3)).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(admin, 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("没有可用的文档分块");
    }

    @Test
    void rejectsWhenChatClientUnavailable() {
        when(chatClientProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.generate(admin, 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("没有可用的对话模型");
    }
}
