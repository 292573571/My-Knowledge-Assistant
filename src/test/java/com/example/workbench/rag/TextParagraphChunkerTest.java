package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextParagraphChunkerTest {

    private final TextParagraphChunker chunker = new TextParagraphChunker();
    private final TextDocumentParser parser = new TextDocumentParser();

    @Test
    void supportsParsedTextDocuments() {
        assertThat(chunker.supports(parser.parse("Notes"))).isTrue();
        assertThat(chunker.supports(new MarkdownDocumentParser().parse("# Notes"))).isFalse();
    }

    @Test
    void chunksShortTextIntoSingleParagraphChunk() {
        List<DocumentChunk> chunks = chunker.chunk(parser.parse("第一段。\n\n第二段。"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("第一段", "第二段");
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(0).chunkType()).isEqualTo("text-paragraph");
    }

    @Test
    void splitsLongTextAndKeepsChunkIndexes() {
        String paragraph = "段落内容".repeat(180) + "\n\n";
        String content = paragraph + paragraph + paragraph;

        List<DocumentChunk> chunks = chunker.chunk(parser.parse(content));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(DocumentChunk::chunkIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }
}
