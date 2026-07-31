package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void parsesTextPdfIntoPageBlocks() throws Exception {
        ParsedDocument document = parser.parse(PdfTestDocuments.textPdf(
                "RAG Guide", "First page knowledge", "Second page retrieval"
        ));

        assertThat(document.documentType()).isEqualTo("pdf");
        assertThat(document.title()).isEqualTo("RAG Guide");
        assertThat(document.blocks()).hasSize(2);
        assertThat(document.blocks()).extracting(DocumentBlock::pageNumber).containsExactly(1, 2);
        assertThat(document.blocks()).extracting(DocumentBlock::blockType).containsOnly("pdf-page");
        assertThat(document.blocks()).allSatisfy(block ->
                assertThat(document.content().substring(block.startOffset(), block.endOffset()))
                        .isEqualTo(block.content())
        );
    }

    @Test
    void identifiesScannedPdfWithoutRunningOcr() throws Exception {
        assertThatThrownBy(() -> parser.parse(PdfTestDocuments.scannedPdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 页", "扫描页", "不支持 OCR");
    }

    @Test
    void rejectsInvalidPdf() {
        assertThatThrownBy(() -> parser.parse("not a pdf".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有效的 PDF");
    }
}
