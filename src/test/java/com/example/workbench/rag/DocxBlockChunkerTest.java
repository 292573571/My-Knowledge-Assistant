package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocxBlockChunkerTest {

    private final DocxBlockChunker chunker = new DocxBlockChunker();

    @Test
    void preservesBlockAndHeadingOrderWhenChunking() {
        ParsedDocument document = new ParsedDocument("docx", "Heading\n\nBody\n\n| A | B |", "Heading", List.of(
                new DocumentBlock("Heading", "docx-heading", "Heading", 1, 0, 7),
                new DocumentBlock("Body", "docx-paragraph", "Heading", 1, 9, 13),
                new DocumentBlock("| A | B |", "docx-table", "Heading", 1, 15, 24)
        ));

        List<DocumentChunk> chunks = chunker.chunk(document);

        assertThat(chunks).extracting(DocumentChunk::content)
                .containsExactly("Heading", "Body", "| A | B |");
        assertThat(chunks).extracting(DocumentChunk::chunkType)
                .containsExactly("docx-heading", "docx-paragraph", "docx-table");
        assertThat(chunks).extracting(DocumentChunk::chunkIndex).containsExactly(0, 1, 2);
        assertThat(chunks).extracting(DocumentChunk::headingPath).containsOnly("Heading");
    }
}
