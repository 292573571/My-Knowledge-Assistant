package com.example.workbench.rag;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentParserRouter {

    private final List<DocumentParser> parsers;

    public DocumentParserRouter(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 根据原始文件名选择解析器。二进制输入确保 PDF 不经过错误的字符解码。
     */
    public ParsedDocument parse(String fileName, byte[] content) {
        return parserFor(fileName)
                .parse(content);
    }

    public DocumentParser parserFor(String fileName) {
        return parsers.stream()
                .filter(parser -> parser.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported document type: " + fileName));
    }

    public ParsedDocument parse(String fileName, String content) {
        return parse(fileName, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
