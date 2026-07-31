package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentParserRouterTest {

    private final DocumentParserRouter router = new DocumentParserRouter(List.of(
            new MarkdownDocumentParser(), new TextDocumentParser(), new PdfDocumentParser(),
            new DocxDocumentParser(), new HtmlDocumentParser()
    ));

    @Test
    void parsesSupportedDocumentUsingFileName() throws Exception {
        assertThat(router.parse("guide.md", "# Guide").documentType()).isEqualTo("markdown");
        assertThat(router.parse("guide.txt", "Guide").documentType()).isEqualTo("text");
        assertThat(router.parse("guide.pdf", PdfTestDocuments.textPdf("Guide", "PDF content")).documentType())
                .isEqualTo("pdf");
        assertThat(router.parse("guide.docx", DocxTestDocuments.simpleDocument("DOCX content")).documentType())
                .isEqualTo("docx");
        assertThat(router.parse("guide.html", "<h1>HTML content</h1>").documentType()).isEqualTo("html");
        assertThat(router.parse("guide.htm", "<p>HTML content</p>").documentType()).isEqualTo("html");
    }

    @Test
    void rejectsUnsupportedDocumentType() {
        assertThatThrownBy(() -> router.parse("guide.xhtml", "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document type");
    }
}
