package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class TextDocumentParser implements DocumentParser {

    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("(?:(?:\\r\\n|\\r|\\n)){2,}");

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        return parseText(decodeUtf8(content));
    }

    private ParsedDocument parseText(String content) {
        List<DocumentBlock> blocks = new ArrayList<>();
        Matcher separator = PARAGRAPH_SEPARATOR.matcher(content);
        int start = 0;
        while (separator.find()) {
            blocks.add(paragraph(content, start, separator.end()));
            start = separator.end();
        }
        if (start < content.length()) {
            blocks.add(paragraph(content, start, content.length()));
        }
        return new ParsedDocument("text", content, extractTitle(content), blocks);
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

    private DocumentBlock paragraph(String content, int start, int end) {
        return new DocumentBlock(content.substring(start, end), "text-paragraph", "", 0, start, end);
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
}
