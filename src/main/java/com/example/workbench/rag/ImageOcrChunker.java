package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 将图片 OCR 文本切成适合向量检索的片段。
 */
@Component
public class ImageOcrChunker implements DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public boolean supports(ParsedDocument document) {
        return "image".equals(document.documentType());
    }

    @Override
    public List<DocumentChunk> chunk(ParsedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < document.content().length()) {
            int end = Math.min(document.content().length(), start + MAX_CHUNK_CHARS);
            String content = document.content().substring(start, end).strip();
            if (!content.isBlank()) {
                chunks.add(new DocumentChunk(content, chunks.size(), "", 0, start, end, "image-ocr"));
            }
            if (end == document.content().length()) break;
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }
        return chunks;
    }
}
