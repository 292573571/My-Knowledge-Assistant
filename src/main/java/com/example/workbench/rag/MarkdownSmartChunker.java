package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MarkdownSmartChunker implements DocumentChunker {

    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int MIN_CHUNK_CHARS = 300;
    private static final int OVERLAP_CHARS = 120;

    @Override
    public boolean supports(ParsedDocument document) {
        return "markdown".equals(document.documentType());
    }

    @Override
    public List<DocumentChunk> chunk(ParsedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();

        for (DocumentBlock section : document.blocks()) {
            if (!"markdown-section".equals(section.blockType()) || section.content().isBlank()) {
                continue;
            }

            if (section.content().length() <= MAX_CHUNK_CHARS) {
                chunks.add(new DocumentChunk(
                        section.content().trim(),
                        chunks.size(),
                        section.headingPath(),
                        section.headingLevel(),
                        section.startOffset(),
                        section.endOffset(),
                        "markdown-section"
                ));
                continue;
            }

            chunks.addAll(splitLongSection(section, chunks.size()));
        }

        return mergeSmallChunks(chunks);
    }

    private List<DocumentChunk> splitLongSection(DocumentBlock section, int initialChunkIndex) {
        List<Block> blocks = parseBlocks(section);
        List<DocumentChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentStart = -1;
        int lastBlockEnd = section.startOffset();

        for (Block block : blocks) {
            if (current.isEmpty()) {
                currentStart = block.startOffset();
            }

            if (!current.isEmpty() && current.length() + block.content().length() > MAX_CHUNK_CHARS) {
                chunks.add(new DocumentChunk(
                        current.toString().trim(),
                        initialChunkIndex + chunks.size(),
                        section.headingPath(),
                        section.headingLevel(),
                        currentStart,
                        lastBlockEnd,
                        "overflow"
                ));

                String overlap = overlapText(current.toString());
                current = new StringBuilder(overlap);
                currentStart = Math.max(section.startOffset(), lastBlockEnd - overlap.length());
            }

            current.append(block.content());
            lastBlockEnd = block.endOffset();
        }

        if (!current.isEmpty()) {
            chunks.add(new DocumentChunk(
                    current.toString().trim(),
                    initialChunkIndex + chunks.size(),
                    section.headingPath(),
                    section.headingLevel(),
                    currentStart,
                    lastBlockEnd,
                    "overflow"
            ));
        }

        return chunks;
    }

    private List<Block> parseBlocks(DocumentBlock section) {
        List<Line> lines = toLines(section.content(), section.startOffset());
        List<Block> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int blockStart = lines.isEmpty() ? section.startOffset() : lines.get(0).startOffset();
        int blockEnd = blockStart;
        boolean inCodeBlock = false;
        boolean inTable = false;

        for (Line line : lines) {
            String trimmed = line.text().trim();
            boolean codeFence = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            boolean tableLine = isTableLine(trimmed);

            if (!inCodeBlock && !inTable && current.length() > 0 && trimmed.isEmpty()) {
                current.append(line.text());
                blockEnd = line.endOffset();
                blocks.add(new Block(current.toString(), blockStart, blockEnd));
                current = new StringBuilder();
                continue;
            }

            if (current.isEmpty()) {
                blockStart = line.startOffset();
            }

            current.append(line.text());
            blockEnd = line.endOffset();

            if (codeFence) {
                inCodeBlock = !inCodeBlock;
            }

            if (!inCodeBlock) {
                if (tableLine) {
                    inTable = true;
                } else if (inTable) {
                    blocks.add(new Block(current.toString(), blockStart, blockEnd));
                    current = new StringBuilder();
                    inTable = false;
                }
            }
        }

        if (!current.isEmpty()) {
            blocks.add(new Block(current.toString(), blockStart, blockEnd));
        }

        return blocks;
    }

    private List<Line> toLines(String content, int baseOffset) {
        List<Line> lines = new ArrayList<>();
        int start = 0;

        while (start < content.length()) {
            int newline = content.indexOf('\n', start);
            int end = newline == -1 ? content.length() : newline + 1;
            lines.add(new Line(content.substring(start, end), baseOffset + start, baseOffset + end));
            start = end;
        }

        return lines;
    }

    private String overlapText(String value) {
        if (value.length() <= OVERLAP_CHARS) {
            return value;
        }

        int start = Math.max(0, value.length() - OVERLAP_CHARS);
        int paragraphStart = value.lastIndexOf("\n\n", start);

        if (paragraphStart >= 0) {
            return value.substring(paragraphStart + 2);
        }

        return value.substring(start);
    }

    private List<DocumentChunk> mergeSmallChunks(List<DocumentChunk> chunks) {
        List<DocumentChunk> merged = new ArrayList<>();
        DocumentChunk pending = null;

        for (DocumentChunk chunk : chunks) {
            if (pending == null) {
                pending = chunk;
                continue;
            }

            if (pending.content().length() < MIN_CHUNK_CHARS
                    && pending.content().length() + chunk.content().length() <= MAX_CHUNK_CHARS
                    && pending.headingPath().equals(chunk.headingPath())) {
                pending = new DocumentChunk(
                        pending.content() + "\n\n" + chunk.content(),
                        pending.chunkIndex(),
                        pending.headingPath(),
                        pending.headingLevel(),
                        pending.startOffset(),
                        chunk.endOffset(),
                        pending.chunkType()
                );
                continue;
            }

            merged.add(reindex(pending, merged.size()));
            pending = chunk;
        }

        if (pending != null) {
            merged.add(reindex(pending, merged.size()));
        }

        return merged;
    }

    private DocumentChunk reindex(DocumentChunk chunk, int chunkIndex) {
        return new DocumentChunk(
                chunk.content(),
                chunkIndex,
                chunk.headingPath(),
                chunk.headingLevel(),
                chunk.startOffset(),
                chunk.endOffset(),
                chunk.chunkType()
        );
    }

    private boolean isTableLine(String trimmed) {
        return trimmed.startsWith("|") && trimmed.endsWith("|");
    }

    private record Line(String text, int startOffset, int endOffset) {
    }

    private record Block(String content, int startOffset, int endOffset) {
    }
}
