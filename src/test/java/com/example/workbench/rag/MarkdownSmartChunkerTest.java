package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownSmartChunkerTest {

    private final MarkdownSmartChunker chunker = new MarkdownSmartChunker();

    @Test
    void supportsMarkdownFilesCaseInsensitively() {
        assertThat(chunker.supports("notes.md")).isTrue();
        assertThat(chunker.supports("NOTES.MD")).isTrue();
        assertThat(chunker.supports("notes.txt")).isFalse();
    }

    @Test
    void chunksMarkdownByHeadingAndKeepsHeadingMetadata() {
        String content = """
                # RAG
                RAG 是检索增强生成。

                ## Chunking
                Chunking 会把长文档切成片段。

                ## Retrieval
                Retrieval 会检索相关片段。
                """;

        List<DocumentChunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(DocumentChunk::chunkIndex).containsExactly(0, 1, 2);
        assertThat(chunks).extracting(DocumentChunk::headingPath)
                .containsExactly("RAG", "RAG > Chunking", "RAG > Retrieval");
        assertThat(chunks).extracting(DocumentChunk::chunkType)
                .containsOnly("markdown-section");
    }

    @Test
    void ignoresHeadingsInsideCodeBlocks() {
        String content = """
                # Real Heading
                ```markdown
                # Fake Heading
                ```
                正文内容。
                """;

        List<DocumentChunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEqualTo("Real Heading");
        assertThat(chunks.get(0).content()).contains("# Fake Heading");
    }
}
