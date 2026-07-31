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
        DocxBlockChunker docx = new DocxBlockChunker();
        HtmlBlockChunker html = new HtmlBlockChunker();
        DocumentChunkerRouter router = new DocumentChunkerRouter(List.of(markdown, text, docx, html));

        assertThat(router.select(new MarkdownDocumentParser().parse("# Guide"))).isSameAs(markdown);
        assertThat(router.select(new TextDocumentParser().parse("Guide"))).isSameAs(text);
        assertThat(router.select(new ParsedDocument("docx", "Guide", null, List.of()))).isSameAs(docx);
        assertThat(router.select(new ParsedDocument("html", "Guide", null, List.of()))).isSameAs(html);
    }

    @Test
    void rejectsUnsupportedDocumentType() {
        DocumentChunkerRouter router = new DocumentChunkerRouter(List.of(new MarkdownSmartChunker(), new TextParagraphChunker()));

        ParsedDocument unsupported = new ParsedDocument("pdf", "content", null, List.of());

        assertThatThrownBy(() -> router.select(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported parsed document type");
    }
}
