package com.example.workbench.rag;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * 将 UTF-8 HTML 清洗并按 DOM 顺序解析为标题、正文、列表、代码和表格块。
 * 本解析器不执行脚本、不抓取远程资源，也不负责将上传 HTML 安全地渲染为网页。
 */
@Component
public class HtmlDocumentParser implements DocumentParser {

    private static final Pattern CHARSET_IN_CONTENT = Pattern.compile("charset\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final String STRUCTURAL_TAGS = "h1,h2,h3,h4,h5,h6,p,ul,ol,pre,table";

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        String html = decodeUtf8(content);
        Document document = Jsoup.parse(html);
        validateDeclaredCharset(document);
        document.select("script,style,noscript,template,iframe,object,embed").remove();

        ParseState state = new ParseState(normalizedText(document.title()));
        visitChildren(document.body(), state, 0, null);
        if (state.blocks.isEmpty()) {
            throw new IllegalArgumentException("HTML 未提取到可索引文本");
        }
        return new ParsedDocument("html", state.fullText.toString(), state.title, state.blocks);
    }

    /**
     * 容器按子节点顺序递归；结构节点拥有其子树，防止 pre/code、table 和嵌套列表重复提取。
     */
    private void visitChildren(Element container, ParseState state, int listDepth, String listType) {
        StringBuilder inline = new StringBuilder();
        for (Node node : container.childNodes()) {
            if (node instanceof TextNode textNode) {
                appendInline(inline, textNode.getWholeText());
                continue;
            }
            if (!(node instanceof Element element)) {
                continue;
            }

            String tag = element.normalName();
            if (isHeading(tag) || element.is("p,ul,ol,pre,table") || isBlockContainer(element)) {
                flushInline(inline, state);
            }

            if (isHeading(tag)) {
                addHeading(element, state, Integer.parseInt(tag.substring(1)));
            } else if ("p".equals(tag)) {
                addBlock(state, normalizedText(inlineText(element)), "html-paragraph");
            } else if ("ul".equals(tag) || "ol".equals(tag)) {
                visitList(element, state, listDepth, tag);
            } else if ("pre".equals(tag)) {
                addCodeBlock(state, element.wholeText());
            } else if ("table".equals(tag)) {
                addBlock(state, tableText(element), "html-table");
            } else if (isBlockContainer(element)) {
                visitChildren(element, state, listDepth, listType);
            } else if ("br".equals(tag)) {
                inline.append('\n');
            } else {
                appendInline(inline, inlineText(element));
            }
        }
        flushInline(inline, state);
    }

    private void addHeading(Element heading, ParseState state, int level) {
        String text = normalizedText(inlineText(heading));
        if (text.isEmpty()) {
            return;
        }
        state.headingStack[level - 1] = text;
        for (int index = level; index < state.headingStack.length; index++) {
            state.headingStack[index] = null;
        }
        state.activeHeadingLevel = level;
        if (state.title == null && level == 1) {
            state.title = text;
        }
        addBlock(state, text, "html-heading");
    }

    private void visitList(Element list, ParseState state, int depth, String listType) {
        int sequence = parsePositiveInt(list.attr("start"), 1);
        for (Element item : list.children()) {
            if (!"li".equals(item.normalName())) {
                continue;
            }
            int itemNumber = parsePositiveInt(item.attr("value"), sequence);
            String marker = "ol".equals(listType) ? itemNumber + ". " : "- ";
            String text = ownListItemText(item);
            if (!text.isBlank()) {
                addBlock(state, "  ".repeat(Math.min(depth, 8)) + marker + text, "html-list-item");
            }
            for (Element child : item.children()) {
                if (child.is("ul,ol")) {
                    visitList(child, state, depth + 1, child.normalName());
                }
            }
            sequence = itemNumber + 1;
        }
    }

    private String ownListItemText(Element item) {
        StringBuilder text = new StringBuilder();
        for (Node node : item.childNodes()) {
            if (node instanceof Element element && element.is("ul,ol")) {
                continue;
            }
            if (node instanceof TextNode textNode) {
                appendInline(text, textNode.getWholeText());
            } else if (node instanceof Element element) {
                appendInline(text, inlineText(element));
            }
        }
        return normalizedText(text.toString());
    }

    private String tableText(Element table) {
        List<String> rows = new ArrayList<>();
        collectTableRows(table, rows);
        return String.join("\n", rows);
    }

    private void collectTableRows(Element parent, List<String> rows) {
        for (Element child : parent.children()) {
            if ("tr".equals(child.normalName())) {
                List<String> cells = new ArrayList<>();
                for (Element cell : child.children()) {
                    if (cell.is("th,td")) {
                        cells.add(escapeTableCell(cell));
                    }
                }
                if (!cells.isEmpty()) {
                    rows.add("| " + String.join(" | ", cells) + " |");
                }
            } else if (child.is("thead,tbody,tfoot")) {
                collectTableRows(child, rows);
            }
        }
    }

    private String escapeTableCell(Element cell) {
        Element copy = cell.clone();
        copy.select("table").remove();
        return normalizedText(inlineText(copy)).replace("\\", "\\\\").replace("|", "\\|")
                .replace("\n", "<br>");
    }

    private String inlineText(Element element) {
        StringBuilder result = new StringBuilder();
        appendInlineNodes(element, result);
        return result.toString();
    }

    private void appendInlineNodes(Element element, StringBuilder result) {
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode textNode) {
                appendInline(result, textNode.getWholeText());
            } else if (node instanceof Element child) {
                if ("br".equals(child.normalName())) {
                    result.append('\n');
                } else if (!child.is("ul,ol,table")) {
                    appendInlineNodes(child, result);
                }
            }
        }
    }

    private void appendInline(StringBuilder target, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!target.isEmpty() && !Character.isWhitespace(target.charAt(target.length() - 1))
                && !Character.isWhitespace(value.charAt(0))) {
            target.append(' ');
        }
        target.append(value);
    }

    private void flushInline(StringBuilder inline, ParseState state) {
        String text = normalizedText(inline.toString());
        if (!text.isEmpty()) {
            addBlock(state, text, "html-paragraph");
        }
        inline.setLength(0);
    }

    private void addCodeBlock(ParseState state, String code) {
        String normalized = code.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.isBlank()) {
            addBlock(state, normalized, "html-code");
        }
    }

    private void addBlock(ParseState state, String content, String blockType) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!state.fullText.isEmpty()) {
            state.fullText.append("\n\n");
        }
        int start = state.fullText.length();
        state.fullText.append(content);
        state.blocks.add(new DocumentBlock(
                content, blockType, headingPath(state.headingStack), state.activeHeadingLevel,
                start, state.fullText.length()
        ));
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

    private boolean isHeading(String tag) {
        return tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6';
    }

    private boolean isBlockContainer(Element element) {
        return element.is("body,main,article,section,div,header,footer,nav,aside,blockquote,form")
                || element.selectFirst(STRUCTURAL_TAGS) != null;
    }

    private String normalizedText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00a0', ' ').replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\R *", "\n").strip();
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String decodeUtf8(byte[] content) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("HTML 文档必须使用 UTF-8 编码", exception);
        }
    }

    private void validateDeclaredCharset(Document document) {
        for (Element meta : document.select("meta[charset],meta[http-equiv]")) {
            String declared = meta.hasAttr("charset") ? meta.attr("charset") : charsetFromContent(meta.attr("content"));
            if (declared.isBlank()) {
                continue;
            }
            try {
                if (!Charset.forName(declared).equals(StandardCharsets.UTF_8)) {
                    throw new IllegalArgumentException("HTML charset 声明必须为 UTF-8");
                }
            } catch (IllegalArgumentException exception) {
                if ("HTML charset 声明必须为 UTF-8".equals(exception.getMessage())) {
                    throw exception;
                }
                throw new IllegalArgumentException("HTML charset 声明无效", exception);
            }
        }
    }

    private String charsetFromContent(String content) {
        Matcher matcher = CHARSET_IN_CONTENT.matcher(content == null ? "" : content);
        return matcher.find() ? matcher.group(1).replace("\"", "").replace("'", "") : "";
    }

    private static final class ParseState {
        private final StringBuilder fullText = new StringBuilder();
        private final List<DocumentBlock> blocks = new ArrayList<>();
        private final String[] headingStack = new String[6];
        private String title;
        private int activeHeadingLevel;

        private ParseState(String title) {
            this.title = title == null || title.isBlank() ? null : title;
        }
    }
}
