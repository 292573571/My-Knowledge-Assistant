package com.example.workbench.rag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 解析 CSV(逗号分隔、UTF-8),按「表头 → 每行 列名: 值」顺序提取可索引文本。
 * 支持带引号字段(引号内可含逗号、换行)。复用 TextParagraphChunker(text 类型)。
 * 无需引入新库。
 */
@Component
public class CsvDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<List<String>> records = parseRecords(text);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("CSV 未提取到可索引文本");
        }
        StringBuilder fullText = new StringBuilder();
        List<DocumentBlock> blocks = new ArrayList<>();
        List<String> header = records.get(0);
        for (int rowIndex = 1; rowIndex < records.size(); rowIndex++) {
            List<String> row = records.get(rowIndex);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            List<String> labeled = new ArrayList<>();
            for (int colIndex = 0; colIndex < row.size(); colIndex++) {
                String key = colIndex < header.size() && !header.get(colIndex).isBlank()
                        ? header.get(colIndex) : ("列" + (colIndex + 1));
                labeled.add(key + ": " + row.get(colIndex).strip());
            }
            addBlock(fullText, blocks, String.join("；", labeled));
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("CSV 未提取到可索引文本");
        }
        return new ParsedDocument("text", fullText.toString(), null, blocks);
    }

    private List<List<String>> parseRecords(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean hasContent = false;
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (inQuotes) {
                if (ch == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index += 2;
                        continue;
                    }
                    inQuotes = false;
                    index++;
                    continue;
                }
                field.append(ch);
                index++;
                continue;
            }
            if (ch == '"') {
                inQuotes = true;
                hasContent = true;
                index++;
                continue;
            }
            if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
                hasContent = false;
                index++;
                continue;
            }
            if (ch == '\r') {
                index++;
                continue;
            }
            if (ch == '\n') {
                current.add(field.toString());
                records.add(current);
                current = new ArrayList<>();
                field.setLength(0);
                hasContent = false;
                index++;
                continue;
            }
            field.append(ch);
            hasContent = true;
            index++;
        }
        if (hasContent || !current.isEmpty() || !field.toString().isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
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
