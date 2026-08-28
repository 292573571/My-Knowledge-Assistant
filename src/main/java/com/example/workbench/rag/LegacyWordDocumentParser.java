package com.example.workbench.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.springframework.stereotype.Component;

/**
 * 解析旧版 Word(.doc, OLE2 格式),提取段落文本。复用 TextParagraphChunker(text 类型)。
 * 依赖 poi-scratchpad(HWPF)。
 */
@Component
public class LegacyWordDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".doc");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content))) {
            StringBuilder fullText = new StringBuilder();
            List<DocumentBlock> blocks = new ArrayList<>();
            Range range = document.getRange();
            for (int index = 0; index < range.numParagraphs(); index++) {
                Paragraph paragraph = range.getParagraph(index);
                String text = paragraph == null || paragraph.text() == null ? "" : paragraph.text().strip();
                if (!text.isBlank()) {
                    addBlock(fullText, blocks, text);
                }
            }
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("DOC 未提取到可索引文本");
            }
            return new ParsedDocument("text", fullText.toString(), null, blocks);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("DOC 文件已损坏或无法解析", exception);
        }
    }

    private void addBlock(StringBuilder fullText, List<DocumentBlock> blocks, String content) {
        if (!fullText.isEmpty()) {
            fullText.append("\n\n");
        }
        int start = fullText.length();
        fullText.append(content);
        blocks.add(new DocumentBlock(content, "text-paragraph", "", 0, start, fullText.length()));
    }
}
