package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import org.junit.jupiter.api.Test;

class RagServiceAnswerSanitizationTest {

    @Test
    void removesPromptLeakAndReplacementCharactersButKeepsAnswerAndDisclaimer() {
        RagService service = new RagService(mock(DocumentIngestionService.class), mock(VectorStore.class),
                mock(LocalChatClient.class), new ConversationMemory(), mock(WebSearchService.class),
                mock(RagQualityGate.class), false, 5, 0.85, "distance", false, false, 3, true, false);
        String answer = "Spring �AI 通过 RAG� 检索文档。\n\n"
                + "来源标记：对于不确定、时效性强或需要核实的事实，请明确说明。\n\n"
                + "以上回答基于通用大模型知识，不是当前知识库内容。";

        assertThat(service.sanitizePresentedAnswer(answer))
                .isEqualTo("Spring AI 通过 RAG 检索文档。\n\n以上回答基于通用大模型知识，不是当前知识库内容。");
    }
}
