package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlBlockChunkerTest {

    private final HtmlBlockChunker chunker = new HtmlBlockChunker();

    @Test
    void preservesDomBlockOrderTypesAndCodeWhitespace() {
        String code = "  line one\n    line two";
        ParsedDocument document = new ParsedDocument("html", "Title\n\n" + code + "\n\n| A | B |", "Title", List.of(
                new DocumentBlock("Title", "html-heading", "Title", 1, 0, 5),
                new DocumentBlock(code, "html-code", "Title", 1, 7, 7 + code.length()),
                new DocumentBlock("| A | B |", "html-table", "Title", 1, 9 + code.length(), 18 + code.length())
        ));

        List<DocumentChunk> chunks = chunker.chunk(document);

        assertThat(chunks).extracting(DocumentChunk::chunkType)
                .containsExactly("html-heading", "html-code", "html-table");
        assertThat(chunks).extracting(DocumentChunk::content)
                .containsExactly("Title", code, "| A | B |");
        assertThat(chunks).extracting(DocumentChunk::chunkIndex).containsExactly(0, 1, 2);
    }
}
