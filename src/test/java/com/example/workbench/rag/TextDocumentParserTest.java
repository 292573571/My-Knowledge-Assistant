package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextDocumentParserTest {

    private final TextDocumentParser parser = new TextDocumentParser();

    @Test
    void supportsTextFilesCaseInsensitively() {
        assertThat(parser.supports("notes.txt")).isTrue();
        assertThat(parser.supports("NOTES.TXT")).isTrue();
        assertThat(parser.supports("notes.md")).isFalse();
    }

    @Test
    void parsesParagraphsWithoutChangingContentOrOffsets() {
        String content = "第一段。\r\n\r\n第二段。";

        ParsedDocument document = parser.parse(content);

        assertThat(document.documentType()).isEqualTo("text");
        assertThat(document.content()).isEqualTo(content);
        assertThat(document.blocks()).hasSize(2);
        assertThat(document.blocks()).allSatisfy(block -> {
            assertThat(block.blockType()).isEqualTo("text-paragraph");
            assertThat(content.substring(block.startOffset(), block.endOffset())).isEqualTo(block.content());
        });
    }

    @Test
    void preservesExistingHashHeadingTitleRuleForText() {
        ParsedDocument document = parser.parse("说明\n# 文本标题\n正文");

        assertThat(document.title()).isEqualTo("文本标题");
    }
}
