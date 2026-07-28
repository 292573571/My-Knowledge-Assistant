package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextParagraphChunkerTest {

    private final TextParagraphChunker chunker = new TextParagraphChunker();

    @Test
    void supportsTextFilesCaseInsensitively() {
        assertThat(chunker.supports("notes.txt")).isTrue();
        assertThat(chunker.supports("NOTES.TXT")).isTrue();
        assertThat(chunker.supports("notes.md")).isFalse();
    }

    @Test
    void chunksShortTextIntoSingleParagraphChunk() {
        List<DocumentChunk> chunks = chunker.chunk("第一段。\n\n第二段。");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("第一段", "第二段");
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(0).chunkType()).isEqualTo("text-paragraph");
    }

    @Test
    void splitsLongTextAndKeepsChunkIndexes() {
        String paragraph = "段落内容".repeat(180) + "\n\n";
        String content = paragraph + paragraph + paragraph;

        List<DocumentChunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(DocumentChunk::chunkIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }
}
