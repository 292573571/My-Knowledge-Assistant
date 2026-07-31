package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TextParagraphChunker implements DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public boolean supports(ParsedDocument document) {
        return "text".equals(document.documentType());
    }

    @Override
    public List<DocumentChunk> chunk(ParsedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentStart = 0;
        int currentEnd = 0;

        for (DocumentBlock paragraph : document.blocks()) {
            if (!"text-paragraph".equals(paragraph.blockType())) {
                continue;
            }
            if (current.isEmpty()) {
                currentStart = paragraph.startOffset();
            }

            if (!current.isEmpty() && current.length() + paragraph.content().length() > MAX_CHUNK_CHARS) {
                chunks.add(new DocumentChunk(
                        current.toString().trim(),
                        chunks.size(),
                        "",
                        0,
                        currentStart,
                        currentEnd,
                        "text-paragraph"
                ));

                String overlap = overlapText(current.toString());
                current = new StringBuilder(overlap);
                currentStart = Math.max(0, currentEnd - overlap.length());
            }

            current.append(paragraph.content());
            currentEnd = paragraph.endOffset();
        }

        if (!current.isEmpty()) {
            chunks.add(new DocumentChunk(
                    current.toString().trim(),
                    chunks.size(),
                    "",
                    0,
                    currentStart,
                    currentEnd,
                    "text-paragraph"
            ));
        }

        return chunks;
    }

    private String overlapText(String value) {
        if (value.length() <= OVERLAP_CHARS) {
            return value;
        }

        return value.substring(value.length() - OVERLAP_CHARS);
    }
}
