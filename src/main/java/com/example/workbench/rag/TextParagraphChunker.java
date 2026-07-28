package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TextParagraphChunker implements DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".txt");
    }

    @Override
    public List<DocumentChunk> chunk(String content) {
        List<DocumentChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentStart = 0;
        int offset = 0;

        for (String paragraph : content.split("(?<=\\R\\R)")) {
            if (current.isEmpty()) {
                currentStart = offset;
            }

            if (!current.isEmpty() && current.length() + paragraph.length() > MAX_CHUNK_CHARS) {
                chunks.add(new DocumentChunk(
                        current.toString().trim(),
                        chunks.size(),
                        "",
                        0,
                        currentStart,
                        offset,
                        "text-paragraph"
                ));

                String overlap = overlapText(current.toString());
                current = new StringBuilder(overlap);
                currentStart = Math.max(0, offset - overlap.length());
            }

            current.append(paragraph);
            offset += paragraph.length();
        }

        if (!current.isEmpty()) {
            chunks.add(new DocumentChunk(
                    current.toString().trim(),
                    chunks.size(),
                    "",
                    0,
                    currentStart,
                    content.length(),
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
