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
 *
 * <p>HWPF 的 {@code paragraph.text()} 会原样返回 Word 域代码：域开始 U+0013、域分隔 U+0014、
 * 域结束 U+0015。目录域（TOC）的指令部分形如 {@code TOC \o "1-3" \h}，条目里还嵌着
 * {@code HYPERLINK \l _Toc123} 与 {@code PAGEREF _Toc123}。这些指令不是正文，直接入库会污染
 * 向量并让检索命中一堆不可读的分块，因此这里做两步清洗：
 * 目录条目整段丢弃；其余段落只保留域结果（U+0014 与 U+0015 之间的可见文本），丢弃域指令。</p>
 */
@Component
public class LegacyWordDocumentParser implements DocumentParser {

    private static final char FIELD_BEGIN = '\u0013';
    private static final char FIELD_SEPARATOR = '\u0014';
    private static final char FIELD_END = '\u0015';

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
                String raw = paragraph == null || paragraph.text() == null ? "" : paragraph.text();
                String text = cleanParagraph(raw);
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

    /**
     * 清洗单个段落的原始文本：丢弃目录条目，剥离域指令，去掉控制字符。
     *
     * @param raw HWPF 返回的段落原文
     * @return 可索引的纯文本，可能为空串
     */
    static String cleanParagraph(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (isTableOfContentsEntry(raw)) {
            return "";
        }
        String visible = visibleFieldText(raw);
        return stripControlCharacters(visible);
    }

    /**
     * 目录条目特征：内含 HYPERLINK 或 PAGEREF 域指令。
     *
     * <p>目录条目在正文中表现为「标题 + 页码」，去掉域代码后只剩碎片，语义价值极低，
     * 且会让「页码类」问题命中错误分块，因此整段丢弃。</p>
     */
    private static boolean isTableOfContentsEntry(String raw) {
        String upper = raw.toUpperCase(Locale.ROOT);
        return upper.contains("HYPERLINK \\L") || upper.contains("PAGEREF") || upper.contains("TOC \\O");
    }

    /**
     * 只保留域的可见结果：域指令（\u0013 与 \u0014 之间）丢弃，域结果（\u0014 与 \u0015 之间）保留。
     * 支持嵌套域（目录条目里 PAGEREF 嵌在 HYPERLINK 结果中）。
     */
    private static String visibleFieldText(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        // 栈内每个元素表示一层域当前处于「指令阶段」还是「结果阶段」。
        List<Boolean> instructionPhase = new ArrayList<>();
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current == FIELD_BEGIN) {
                instructionPhase.add(Boolean.TRUE);
                continue;
            }
            if (current == FIELD_SEPARATOR) {
                if (!instructionPhase.isEmpty()) {
                    instructionPhase.set(instructionPhase.size() - 1, Boolean.FALSE);
                }
                continue;
            }
            if (current == FIELD_END) {
                if (!instructionPhase.isEmpty()) {
                    instructionPhase.remove(instructionPhase.size() - 1);
                }
                continue;
            }
            if (instructionPhase.contains(Boolean.TRUE)) {
                continue;
            }
            builder.append(current);
        }
        return builder.toString();
    }

    private static String stripControlCharacters(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\t' || current == '\n') {
                builder.append(' ');
                continue;
            }
            if (current < 0x20 || current == 0x7f) {
                continue;
            }
            builder.append(current);
        }
        return builder.toString().replaceAll("\\s{2,}", " ").strip();
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
