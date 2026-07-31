package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    @Test
    void supportsMarkdownFilesCaseInsensitively() {
        assertThat(parser.supports("guide.md")).isTrue();
        assertThat(parser.supports("GUIDE.MD")).isTrue();
        assertThat(parser.supports("guide.txt")).isFalse();
    }

    @Test
    void parsesTitleSectionsAndOffsets() {
        String content = "# RAG\n介绍。\n\n## Chunking\n切片说明。";

        ParsedDocument document = parser.parse(content);

        assertThat(document.documentType()).isEqualTo("markdown");
        assertThat(document.content()).isEqualTo(content);
        assertThat(document.title()).isEqualTo("RAG");
        assertThat(document.blocks()).hasSize(2);
        assertThat(document.blocks()).extracting(DocumentBlock::headingPath)
                .containsExactly("RAG", "RAG > Chunking");
        assertThat(document.blocks()).extracting(DocumentBlock::headingLevel).containsExactly(1, 2);
        assertThat(document.blocks()).allSatisfy(block ->
                assertThat(content.substring(block.startOffset(), block.endOffset())).isEqualTo(block.content())
        );
    }

    @Test
    void keepsCodeFenceHeadingInsideCurrentSection() {
        String content = "# Real\n```markdown\n# Fake\n```\n正文";

        ParsedDocument document = parser.parse(content);

        assertThat(document.title()).isEqualTo("Real");
        assertThat(document.blocks()).singleElement().satisfies(block -> {
            assertThat(block.headingPath()).isEqualTo("Real");
            assertThat(block.content()).contains("# Fake");
        });
    }
}
