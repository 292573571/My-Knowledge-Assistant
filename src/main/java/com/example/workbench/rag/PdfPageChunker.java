package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 在单个 PDF 页面内切片，保证每个 chunk 都能映射到唯一页码。
 */
@Component
public class PdfPageChunker implements DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public boolean supports(ParsedDocument document) {
        return "pdf".equals(document.documentType());
    }

    @Override
    public List<DocumentChunk> chunk(ParsedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (DocumentBlock page : document.blocks()) {
            if (!"pdf-page".equals(page.blockType()) || page.content().isBlank()) {
                continue;
            }
            appendPageChunks(page, chunks);
        }
        return chunks;
    }

    private void appendPageChunks(DocumentBlock page, List<DocumentChunk> chunks) {
        int start = 0;
        while (start < page.content().length()) {
            int end = Math.min(page.content().length(), start + MAX_CHUNK_CHARS);
            if (end < page.content().length()) {
                int paragraphEnd = page.content().lastIndexOf("\n\n", end);
                if (paragraphEnd > start) {
                    end = paragraphEnd;
                }
            }
            String content = page.content().substring(start, end).trim();
            if (!content.isEmpty()) {
                chunks.add(new DocumentChunk(
                        content, chunks.size(), "", 0,
                        page.startOffset() + start, page.startOffset() + end, "pdf-page", page.pageNumber()
                ));
            }
            if (end == page.content().length()) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }
    }
}
