package com.example.workbench.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * 按 DOCX 主文档 body 的原始顺序解析标题、正文、列表和表格。
 * 页眉、页脚、批注、文本框和图片文字不属于本解析器的职责。
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    private static final Pattern HEADING_STYLE = Pattern.compile("(?:heading|标题)([1-9])");

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            return parseDocument(document);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException
                    && illegalArgumentException.getMessage() != null
                    && illegalArgumentException.getMessage().startsWith("DOCX ")) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("DOCX 文件已损坏或无法解析", exception);
        }
    }

    private ParsedDocument parseDocument(XWPFDocument document) {
        StringBuilder fullText = new StringBuilder();
        List<DocumentBlock> blocks = new ArrayList<>();
        String[] headingStack = new String[9];
        Map<String, Integer> listCounters = new HashMap<>();
        String title = normalizedTitle(document.getProperties().getCoreProperties().getTitle());
        int activeHeadingLevel = 0;

        // getBodyElements() 同时包含段落和表格，是保持两类元素原始交错顺序的关键。
        for (IBodyElement element : document.getBodyElements()) {
            if (element.getElementType() == BodyElementType.PARAGRAPH) {
                XWPFParagraph paragraph = (XWPFParagraph) element;
                String paragraphText = paragraph.getText().strip();
                if (paragraphText.isEmpty()) {
                    continue;
                }

                int headingLevel = headingLevel(paragraph, document.getStyles());
                if (headingLevel > 0) {
                    updateHeadingStack(headingStack, headingLevel, paragraphText);
                    activeHeadingLevel = headingLevel;
                    if (title == null && headingLevel == 1) {
                        title = paragraphText;
                    }
                    addBlock(fullText, blocks, paragraphText, "docx-heading",
                            headingPath(headingStack), headingLevel);
                    continue;
                }

                boolean listItem = paragraph.getNumID() != null;
                String blockText = listItem ? listText(paragraph, listCounters) : paragraphText;
                addBlock(fullText, blocks, blockText, listItem ? "docx-list-item" : "docx-paragraph",
                        headingPath(headingStack), activeHeadingLevel);
                continue;
            }

            if (element.getElementType() == BodyElementType.TABLE) {
                String tableText = tableText((XWPFTable) element);
                if (!tableText.isBlank()) {
                    addBlock(fullText, blocks, tableText, "docx-table",
                            headingPath(headingStack), activeHeadingLevel);
                }
            }
        }

        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("DOCX 未提取到可索引文本");
        }
        return new ParsedDocument("docx", fullText.toString(), title, blocks);
    }

    /**
     * 标题识别优先使用语言无关的 outline level，再回退到常见中英文样式名称。
     */
    private int headingLevel(XWPFParagraph paragraph, XWPFStyles styles) {
        if (paragraph.getCTP().getPPr() != null && paragraph.getCTP().getPPr().isSetOutlineLvl()) {
            return outlineLevel(paragraph.getCTP().getPPr().getOutlineLvl().getVal());
        }

        String styleId = paragraph.getStyle();
        Set<String> visited = new HashSet<>();
        while (styles != null && styleId != null && !styleId.isBlank() && visited.add(styleId)) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style == null) {
                break;
            }
            if (style.getCTStyle().getPPr() != null && style.getCTStyle().getPPr().isSetOutlineLvl()) {
                return outlineLevel(style.getCTStyle().getPPr().getOutlineLvl().getVal());
            }
            int namedLevel = namedHeadingLevel(style.getStyleId(), style.getName());
            if (namedLevel > 0) {
                return namedLevel;
            }
            styleId = style.getBasisStyleID();
        }
        return namedHeadingLevel(paragraph.getStyle(), null);
    }

    private int outlineLevel(BigInteger outlineLevel) {
        int value = outlineLevel == null ? 9 : outlineLevel.intValue();
        return value >= 0 && value <= 8 ? value + 1 : 0;
    }

    private int namedHeadingLevel(String... names) {
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
            Matcher matcher = HEADING_STYLE.matcher(normalized);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return 0;
    }

    private void updateHeadingStack(String[] stack, int level, String text) {
        stack[level - 1] = text;
        for (int index = level; index < stack.length; index++) {
            stack[index] = null;
        }
    }

    private String headingPath(String[] stack) {
        List<String> headings = new ArrayList<>();
        for (String heading : stack) {
            if (heading != null && !heading.isBlank()) {
                headings.add(heading);
            }
        }
        return String.join(" > ", headings);
    }

    private String listText(XWPFParagraph paragraph, Map<String, Integer> counters) {
        BigInteger levelValue = paragraph.getNumIlvl();
        int level = levelValue == null ? 0 : Math.max(0, levelValue.intValue());
        String format = paragraph.getNumFmt();
        String indent = "  ".repeat(Math.min(level, 8));
        if (format == null || "bullet".equalsIgnoreCase(format)) {
            return indent + "- " + paragraph.getText().strip();
        }

        String key = paragraph.getNumID() + ":" + level;
        int sequence = counters.merge(key, 1, Integer::sum);
        counters.keySet().removeIf(counterKey -> deeperListLevel(counterKey, paragraph.getNumID(), level));
        return indent + sequence + ". " + paragraph.getText().strip();
    }

    private boolean deeperListLevel(String key, BigInteger numberId, int level) {
        String prefix = numberId + ":";
        return key.startsWith(prefix) && Integer.parseInt(key.substring(prefix.length())) > level;
    }

    private String tableText(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText().strip().replace("\\", "\\\\").replace("|", "\\|")
                        .replaceAll("\\R+", "<br>"));
            }
            rows.add("| " + String.join(" | ", cells) + " |");
        }
        return String.join("\n", rows).strip();
    }

    private void addBlock(
            StringBuilder fullText, List<DocumentBlock> blocks, String content,
            String blockType, String headingPath, int headingLevel
    ) {
        if (!fullText.isEmpty()) {
            fullText.append("\n\n");
        }
        int start = fullText.length();
        fullText.append(content);
        blocks.add(new DocumentBlock(
                content, blockType, headingPath, headingLevel, start, fullText.length()
        ));
    }

    private String normalizedTitle(String title) {
        return title == null || title.isBlank() ? null : title.strip();
    }
}
