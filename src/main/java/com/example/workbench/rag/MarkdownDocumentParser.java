package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        return parseText(decodeUtf8(content));
    }

    private ParsedDocument parseText(String content) {
        List<DocumentBlock> blocks = new ArrayList<>();
        List<Line> lines = toLines(content);
        String[] headingStack = new String[6];
        int sectionStart = 0;
        int sectionHeadingLevel = 0;
        String sectionHeadingPath = "";
        boolean hasActiveSection = false;
        boolean inCodeBlock = false;

        for (Line line : lines) {
            String trimmed = line.text().trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeBlock = !inCodeBlock;
            }

            Heading heading = inCodeBlock ? null : parseHeading(line.text());
            if (heading == null) {
                continue;
            }

            if (hasActiveSection) {
                blocks.add(section(content, sectionStart, line.startOffset(), sectionHeadingPath, sectionHeadingLevel));
            }

            headingStack[heading.level() - 1] = heading.text();
            for (int index = heading.level(); index < headingStack.length; index++) {
                headingStack[index] = null;
            }

            sectionStart = line.startOffset();
            sectionHeadingLevel = heading.level();
            sectionHeadingPath = buildHeadingPath(headingStack);
            hasActiveSection = true;
        }

        if (hasActiveSection) {
            blocks.add(section(content, sectionStart, content.length(), sectionHeadingPath, sectionHeadingLevel));
        } else if (!content.isBlank()) {
            blocks.add(section(content, 0, content.length(), "", 0));
        }

        return new ParsedDocument("markdown", content, extractTitle(content), blocks);
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString()
                    .trim();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("文档必须使用 UTF-8 编码", exception);
        }
    }

    private DocumentBlock section(String content, int start, int end, String headingPath, int headingLevel) {
        return new DocumentBlock(
                content.substring(start, end), "markdown-section", headingPath, headingLevel, start, end
        );
    }

    private List<Line> toLines(String content) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int newline = content.indexOf('\n', start);
            int end = newline == -1 ? content.length() : newline + 1;
            lines.add(new Line(content.substring(start, end), start, end));
            start = end;
        }
        return lines;
    }

    private Heading parseHeading(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6 || level >= line.length() || line.charAt(level) != ' ') {
            return null;
        }
        return new Heading(level, line.substring(level + 1).trim());
    }

    private String buildHeadingPath(String[] headingStack) {
        List<String> headings = new ArrayList<>();
        for (String heading : headingStack) {
            if (heading != null && !heading.isBlank()) {
                headings.add(heading);
            }
        }
        return String.join(" > ", headings);
    }

    private String extractTitle(String content) {
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst()
                .orElse(null);
    }

    private record Heading(int level, String text) {
    }

    private record Line(String text, int startOffset, int endOffset) {
    }
}
