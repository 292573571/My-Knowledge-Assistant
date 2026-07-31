package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PdfPageChunkerTest {

    private final PdfPageChunker chunker = new PdfPageChunker();

    @Test
    void keepsChunksWithinTheirSourcePage() {
        String firstPage = "first ".repeat(300);
        String secondPage = "second page";
        ParsedDocument document = new ParsedDocument("pdf", firstPage + "\n\n" + secondPage, null, List.of(
                new DocumentBlock(firstPage, "pdf-page", "", 0, 0, firstPage.length(), 1),
                new DocumentBlock(secondPage, "pdf-page", "", 0,
                        firstPage.length() + 2, firstPage.length() + 2 + secondPage.length(), 2)
        ));

        List<DocumentChunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).extracting(DocumentChunk::chunkIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).filteredOn(chunk -> chunk.pageNumber() == 1)
                .allSatisfy(chunk -> assertThat(chunk.content()).doesNotContain("second page"));
        assertThat(chunks.get(chunks.size() - 1).pageNumber()).isEqualTo(2);
    }
}
