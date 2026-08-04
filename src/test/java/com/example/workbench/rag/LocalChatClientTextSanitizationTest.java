package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.memory.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class LocalChatClientTextSanitizationTest {

    @Test
    void removesUnicodeReplacementCharactersReturnedByUpstreamModel() {
        LocalChatClient client = client();

        assertThat(client.sanitizeModelText("Spring �AI 通过 RAG� 检索文档"))
                .isEqualTo("Spring AI 通过 RAG 检索文档");
    }

    @Test
    void keepsValidChineseEmojiAndSupplementaryCharacters() {
        LocalChatClient client = client();
        String valid = "中文、🙂、𠮷";

        assertThat(client.sanitizeModelText(valid)).isEqualTo(valid);
        assertThat(client.sanitizeModelText(null)).isNull();
    }

    @Test
    void convertsConversationHistoryToRealRoleMessages() {
        LocalChatClient client = client();

        assertThat(client.toSpringAiMessages(List.of(
                new ChatMessage("user", "RAG 是什么？"),
                new ChatMessage("assistant", "RAG 是检索增强生成。")
        )))
                .satisfiesExactly(
                        message -> assertThat(message).isInstanceOf(UserMessage.class),
                        message -> assertThat(message).isInstanceOf(AssistantMessage.class));
    }

    private LocalChatClient client() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        return new LocalChatClient(beanFactory.getBeanProvider(org.springframework.ai.chat.client.ChatClient.class),
                "https://example.invalid", "test-model", "", 1, 0, 1000, 1000, "local-answer", 0, 1200);
    }
}
