package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChunkerRouterTest {

    @Test
    void selectsFirstSupportingChunker() {
        MarkdownSmartChunker markdown = new MarkdownSmartChunker();
        TextParagraphChunker text = new TextParagraphChunker();
        DocumentChunkerRouter router = new DocumentChunkerRouter(List.of(markdown, text));

        assertThat(router.select("guide.md")).isSameAs(markdown);
        assertThat(router.select("guide.txt")).isSameAs(text);
    }

    @Test
    void rejectsUnsupportedDocumentType() {
        DocumentChunkerRouter router = new DocumentChunkerRouter(List.of(new MarkdownSmartChunker(), new TextParagraphChunker()));

        assertThatThrownBy(() -> router.select("guide.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document type");
    }
}
