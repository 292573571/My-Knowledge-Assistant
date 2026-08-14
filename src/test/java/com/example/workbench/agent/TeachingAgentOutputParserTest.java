package com.example.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TeachingAgentOutputParserTest {

    private final TeachingAgentOutputParser parser = new TeachingAgentOutputParser(new ObjectMapper());

    @Test
    void parsesStructuredJson() {
        TeachingAgentDraft draft = parser.parse("{\"topic\":\"RAG 检索增强\",\"explanation\":\"先检索，再回答。\",\"checkQuestion\":\"为什么要先检索？\"}");

        assertThat(draft.topic()).isEqualTo("RAG 检索增强");
        assertThat(draft.explanation()).isEqualTo("先检索，再回答。");
        assertThat(draft.checkQuestion()).isEqualTo("为什么要先检索？");
    }

    @Test
    void parsesJsonInsideCodeFence() {
        TeachingAgentDraft draft = parser.parse("```json\n{\"topic\":\"Agent\",\"explanation\":\"讲解\",\"checkQuestion\":\"问题？\"}\n```");

        assertThat(draft.topic()).isEqualTo("Agent");
        assertThat(draft.checkQuestion()).isEqualTo("问题？");
    }

    @Test
    void fallsBackToRawTextWhenModelDoesNotReturnValidStructure() {
        TeachingAgentDraft draft = parser.parse("普通讲解\n检查问题？");

        assertThat(draft.explanation()).isEqualTo("普通讲解\n检查问题？");
        assertThat(draft.topic()).isNull();
        assertThat(draft.checkQuestion()).isNull();
    }
}
