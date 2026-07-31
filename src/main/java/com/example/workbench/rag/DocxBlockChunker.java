package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 按 DOCX 解析块的原始顺序切片，不跨标题路径重排或合并内容。
 */
@Component
public class DocxBlockChunker implements DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public boolean supports(ParsedDocument document) {
        return "docx".equals(document.documentType());
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
            String content = block.content().substring(start, end).trim();
            if (!content.isEmpty()) {
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
