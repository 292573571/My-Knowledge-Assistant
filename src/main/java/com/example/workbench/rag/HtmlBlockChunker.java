package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 按 HTML DOM 解析块的原始顺序切片，并保留标题路径、代码块和表格类型。
 */
@Component
public class HtmlBlockChunker implements DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public boolean supports(ParsedDocument document) {
        return "html".equals(document.documentType());
    }

    @Override
    public List<DocumentChunk> chunk(ParsedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (DocumentBlock block : document.blocks()) {
            appendBlock(block, chunks);
        }
        return chunks;
    }

    private void appendBlock(DocumentBlock block, List<DocumentChunk> chunks) {
        int start = 0;
        while (start < block.content().length()) {
            int end = Math.min(block.content().length(), start + MAX_CHUNK_CHARS);
            if (end < block.content().length()) {
                int lineEnd = block.content().lastIndexOf('\n', end);
                if (lineEnd > start) {
                    end = lineEnd;
                }
            }
            if (end > start && Character.isHighSurrogate(block.content().charAt(end - 1))) {
                end--;
            }
            String content = block.content().substring(start, end);
            if (!content.isBlank()) {
                chunks.add(new DocumentChunk(
                        content, chunks.size(), block.headingPath(), block.headingLevel(),
                        block.startOffset() + start, block.startOffset() + end, block.blockType()
                ));
            }
            if (end == block.content().length()) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }
    }
}
