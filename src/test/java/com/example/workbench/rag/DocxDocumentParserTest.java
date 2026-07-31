package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    void parsesHeadingsParagraphsListsAndTablesInBodyOrder() throws Exception {
        ParsedDocument document = parser.parse(DocxTestDocuments.structuredDocument());

        assertThat(document.documentType()).isEqualTo("docx");
        assertThat(document.title()).isEqualTo("Architecture Guide");
        assertThat(document.blocks()).extracting(DocumentBlock::blockType).containsExactly(
                "docx-heading", "docx-paragraph", "docx-table", "docx-heading",
                "docx-list-item", "docx-list-item", "docx-paragraph"
        );
        assertThat(document.blocks()).extracting(DocumentBlock::headingPath).containsExactly(
                "Architecture", "Architecture", "Architecture", "Architecture > Processing",
                "Architecture > Processing", "Architecture > Processing", "Architecture > Processing"
        );
        assertThat(document.blocks().get(2).content())
                .contains("| Component | Purpose |", "| Parser | Keep body order |");
        assertThat(document.blocks().get(4).content()).isEqualTo("1. Parse blocks");
        assertThat(document.blocks().get(5).content()).isEqualTo("2. Create chunks");
        assertThat(document.content())
                .containsSubsequence("Introduction before table", "Component", "Processing", "Parse blocks");
        assertThat(document.blocks()).allSatisfy(block ->
                assertThat(document.content().substring(block.startOffset(), block.endOffset()))
                        .isEqualTo(block.content())
        );
    }

    @Test
    void rejectsInvalidDocx() {
        assertThatThrownBy(() -> parser.parse("not a docx".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOCX 文件已损坏或无法解析");
    }
}
