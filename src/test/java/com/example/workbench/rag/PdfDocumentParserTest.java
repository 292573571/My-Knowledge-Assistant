package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PdfDocumentParserTest {

    private final OcrEngine ocrEngine = org.mockito.Mockito.mock(OcrEngine.class);
    private final PdfDocumentParser parser = new PdfDocumentParser(ocrEngine);

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
    void recognizesScannedPdfAndPreservesPageNumber() throws Exception {
        org.mockito.Mockito.when(ocrEngine.recognize(org.mockito.Mockito.any())).thenReturn("扫描页中的知识内容");

        ParsedDocument document = parser.parse(PdfTestDocuments.scannedPdf());

        assertThat(document.content()).isEqualTo("扫描页中的知识内容");
        assertThat(document.blocks()).singleElement().satisfies(block -> {
            assertThat(block.pageNumber()).isEqualTo(1);
            assertThat(block.blockType()).isEqualTo("pdf-page");
        });
    }

    @Test
    void usesNativeTextAndOcrForMixedPdf() throws Exception {
        org.mockito.Mockito.when(ocrEngine.recognize(org.mockito.Mockito.any())).thenReturn("扫描页知识");

        ParsedDocument document = parser.parse(PdfTestDocuments.mixedPdf());

        assertThat(document.content()).contains("Native text page knowledge", "扫描页知识");
        assertThat(document.blocks()).extracting(DocumentBlock::pageNumber).containsExactly(1, 2);
        org.mockito.Mockito.verify(ocrEngine, org.mockito.Mockito.times(1))
                .recognize(org.mockito.Mockito.any());
    }

    @Test
    void usesOcrWhenPdfTextLayerIsFragmented() throws Exception {
        org.mockito.Mockito.when(ocrEngine.recognize(org.mockito.Mockito.any()))
                .thenReturn("ZOOM65 VIA 使用教程 正常识别内容");

        ParsedDocument document = parser.parse(PdfTestDocuments.fragmentedTextPdf());

        assertThat(document.content()).isEqualTo("ZOOM65 VIA 使用教程 正常识别内容");
        org.mockito.Mockito.verify(ocrEngine).recognize(org.mockito.Mockito.any());
    }

    @Test
    void usesOcrWhenNormalParagraphIsFollowedByFragmentedLines() throws Exception {
        org.mockito.Mockito.when(ocrEngine.recognize(org.mockito.Mockito.any()))
                .thenReturn("Agent 由三个主要部分组成：感知、推理和行动。");

        ParsedDocument document = parser.parse(PdfTestDocuments.mixedFragmentedTextPdf());

        assertThat(document.content()).isEqualTo("Agent 由三个主要部分组成：感知、推理和行动。");
        org.mockito.Mockito.verify(ocrEngine).recognize(org.mockito.Mockito.any());
    }

    @Test
    void removesRepeatedShortWatermarkLinesAcrossPages() throws Exception {
        ParsedDocument document = parser.parse(PdfTestDocuments.repeatedLinePdf(
                "CONFIDENTIAL COPY", "First page knowledge", "Second page knowledge", "Third page knowledge"
        ));

        assertThat(document.content()).doesNotContain("CONFIDENTIAL COPY")
                .contains("First page knowledge", "Second page knowledge", "Third page knowledge");
        assertThat(document.blocks()).extracting(DocumentBlock::pageNumber).containsExactly(1, 2, 3);
    }

    @Test
    void keepsRepeatedLineWhenItIsTheOnlyPageContent() throws Exception {
        ParsedDocument document = parser.parse(PdfTestDocuments.repeatedLinePdf(
                "Required legal statement", "", "", ""
        ));

        assertThat(document.blocks()).hasSize(3);
        assertThat(document.content()).contains("Required legal statement");
    }

    @Test
    void keepsRepeatedLinesInShortPdfWithoutEnoughEvidence() throws Exception {
        ParsedDocument document = parser.parse(PdfTestDocuments.repeatedLinePdf(
                "Shared section title", "First page", "Second page"
        ));

        assertThat(document.content()).contains("Shared section title");
    }

    @Test
    void rejectsScannedPdfWhenOcrFindsNoText() throws Exception {
        org.mockito.Mockito.when(ocrEngine.recognize(org.mockito.Mockito.any())).thenReturn(" ");

        assertThatThrownBy(() -> parser.parse(PdfTestDocuments.scannedPdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 页", "OCR", "未识别到文字");
    }

    @Test
    void rejectsInvalidPdf() {
        assertThatThrownBy(() -> parser.parse("not a pdf".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有效的 PDF");
    }

    @Test
    void rejectsPdfAboveConfiguredPageLimit() throws Exception {
        PdfDocumentParser limitedParser = new PdfDocumentParser(ocrEngine, 1, 50, 14400, 14400, 25_000_000L);

        assertThatThrownBy(() -> limitedParser.parse(PdfTestDocuments.textPdf("Too many pages", "one", "two")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("页数");
    }

    @Test
    void rejectsOcrPageAboveConfiguredPixelLimitBeforeRendering() throws Exception {
        PdfDocumentParser limitedParser = new PdfDocumentParser(ocrEngine, 200, 50, 14400, 14400, 1);

        assertThatThrownBy(() -> limitedParser.parse(PdfTestDocuments.scannedPdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尺寸或 OCR 像素数");
        org.mockito.Mockito.verifyNoInteractions(ocrEngine);
    }

    @Test
    void rejectsOcrPageWhenConfiguredOcrPageLimitIsReached() throws Exception {
        PdfDocumentParser limitedParser = new PdfDocumentParser(ocrEngine, 200, 0, 14400, 14400, 25_000_000L);

        assertThatThrownBy(() -> limitedParser.parse(PdfTestDocuments.scannedPdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OCR 页数");
        org.mockito.Mockito.verifyNoInteractions(ocrEngine);
    }
}
