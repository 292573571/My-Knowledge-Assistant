package com.example.workbench.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 解析 JSON / JSON Lines,将结构扁平化为「路径: 值」文本行,便于向量切片检索。
 * 复用 TextParagraphChunker(text 类型)。Jackson 已由 Spring Boot 提供,无需新库。
 */
@Component
public class JsonDocumentParser implements DocumentParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".jsonl");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(text);
            flatten(root, "", lines);
        } catch (Exception exception) {
            throw new IllegalArgumentException("JSON 文件格式无效或无法解析", exception);
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("JSON 未提取到可索引文本");
        }
        StringBuilder fullText = new StringBuilder();
        List<DocumentBlock> blocks = new ArrayList<>();
        for (String line : lines) {
            addBlock(fullText, blocks, line);
        }
        return new ParsedDocument("text", fullText.toString(), null, blocks);
    }

    private void flatten(JsonNode node, String prefix, List<String> out) {
        if (node == null || node.isNull()) {
            if (!prefix.isEmpty()) {
                out.add(prefix + ": (空)");
            }
            return;
        }
        if (node.isObject()) {
            boolean empty = true;
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                empty = false;
                String childPrefix = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flatten(entry.getValue(), childPrefix, out);
            }
            if (empty && !prefix.isEmpty()) {
                out.add(prefix + ": {}");
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                flatten(node.get(index), prefix + "[" + index + "]", out);
            }
            return;
        }
        String value = node.isTextual() ? node.asText() : node.toString();
        out.add((prefix.isEmpty() ? "" : prefix + ": ") + value);
    }

    private void addBlock(StringBuilder fullText, List<DocumentBlock> blocks, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!fullText.isEmpty()) {
            fullText.append("\n\n");
        }
        int start = fullText.length();
        fullText.append(content);
        blocks.add(new DocumentBlock(content, "text-paragraph", "", 0, start, fullText.length()));
    }
}
