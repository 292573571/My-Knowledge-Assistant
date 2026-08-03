package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImageOcrChunkerTest {

    @Test
    void chunksOcrText() {
        String text = "图片 OCR 内容。".repeat(121);
        ParsedDocument document = new ParsedDocument("image", text, null,
                List.of(new DocumentBlock(text, "image-ocr", "", 0, 0, text.length(), 0)));

        List<DocumentChunk> chunks = new ImageOcrChunker().chunk(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(DocumentChunk::chunkType).containsOnly("image-ocr");
    }
}
