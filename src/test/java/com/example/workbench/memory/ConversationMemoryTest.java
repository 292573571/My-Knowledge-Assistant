package com.example.workbench.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationMemoryTest {

    @Test
    void returnsOnlyTheMostRecentCompleteRounds() {
        ConversationMemory memory = new ConversationMemory();
        for (int round = 1; round <= 6; round++) {
            memory.addUserMessage("conversation-1", "问题 " + round);
            memory.addAssistantMessage("conversation-1", "回答 " + round);
        }

        assertThat(memory.recent("conversation-1", 4))
                .extracting(ChatMessage::content)
                .containsExactly("问题 3", "回答 3", "问题 4", "回答 4", "问题 5", "回答 5", "问题 6", "回答 6");
    }
}
