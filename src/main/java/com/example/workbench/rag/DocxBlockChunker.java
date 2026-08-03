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
        List<DocumentBlock> section = new ArrayList<>();
        for (DocumentBlock block : document.blocks()) {
            if ("docx-heading".equals(block.blockType()) && !section.isEmpty()) {
                appendSection(section, chunks);
                section.clear();
            }
            section.add(block);
        }
        if (!section.isEmpty()) {
            appendSection(section, chunks);
        }
        return chunks;
    }

    private void appendSection(List<DocumentBlock> blocks, List<DocumentChunk> chunks) {
        DocumentBlock first = blocks.get(0);
        String content = blocks.stream()
                .map(DocumentBlock::content)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        if (!content.isBlank()) {
            appendBlock(new DocumentBlock(
                    content,
                    "docx-section",
                    first.headingPath(),
                    first.headingLevel(),
                    first.startOffset(),
                    blocks.get(blocks.size() - 1).endOffset(),
                    first.pageNumber()
            ), chunks);
        }
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
