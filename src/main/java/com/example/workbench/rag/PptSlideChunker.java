package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 在单个幻灯片内切片，保证每个 chunk 都能映射到唯一的幻灯片页码。
 */
@Component
public class PptSlideChunker implements DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public boolean supports(ParsedDocument document) {
        return PowerPointDocumentParser.DOCUMENT_TYPE.equals(document.documentType());
    }

    @Override
    public List<DocumentChunk> chunk(ParsedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (DocumentBlock slide : document.blocks()) {
            if (!PowerPointDocumentParser.SLIDE_BLOCK_TYPE.equals(slide.blockType()) || slide.content().isBlank()) {
                continue;
            }
            appendSlideChunks(slide, chunks);
        }
        return chunks;
    }

    private void appendSlideChunks(DocumentBlock slide, List<DocumentChunk> chunks) {
        int start = 0;
        while (start < slide.content().length()) {
            int end = Math.min(slide.content().length(), start + MAX_CHUNK_CHARS);
            if (end < slide.content().length()) {
                int paragraphEnd = slide.content().lastIndexOf("\n\n", end);
                if (paragraphEnd > start) {
                    end = paragraphEnd;
                }
            }
            String content = slide.content().substring(start, end).trim();
            if (!content.isEmpty()) {
                chunks.add(new DocumentChunk(
                        content, chunks.size(), "", 0,
                        slide.startOffset() + start, slide.startOffset() + end,
                        PowerPointDocumentParser.SLIDE_BLOCK_TYPE, slide.pageNumber()
                ));
            }
            if (end == slide.content().length()) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }
    }
}
