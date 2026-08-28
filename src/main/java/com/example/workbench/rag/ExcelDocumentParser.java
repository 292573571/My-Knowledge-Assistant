package com.example.workbench.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 解析 Excel 工作簿(.xlsx / .xls),按「工作表名 → 表头 → 每行 列名: 值」的顺序提取可索引文本。
 * 复用 TextParagraphChunker,因此输出 documentType 为 text、块类型为 text-paragraph。
 * .xlsx 与 .xls 同属 poi-ooxml 依赖,无需引入新库。
 */
@Component
public class ExcelDocumentParser implements DocumentParser {

    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0};

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        boolean xlsx = isZip(content);
        try (Workbook workbook = xlsx
                ? new XSSFWorkbook(new ByteArrayInputStream(content))
                : new HSSFWorkbook(new ByteArrayInputStream(content))) {
            return parseWorkbook(workbook);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Excel 文件已损坏或无法解析", exception);
        }
    }

    private ParsedDocument parseWorkbook(Workbook workbook) {
        StringBuilder fullText = new StringBuilder();
        List<DocumentBlock> blocks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) {
                continue;
            }
            String sheetName = sheet.getSheetName();
            if (sheetName != null && !sheetName.isBlank()) {
                addBlock(fullText, blocks, "工作表: " + sheetName);
            }
            boolean headerSeen = false;
            List<String> header = new ArrayList<>();
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                boolean any = false;
                for (Cell cell : row) {
                    String text = cell == null ? "" : formatter.formatCellValue(cell).strip();
                    cells.add(text);
                    if (!text.isBlank()) {
                        any = true;
                    }
                }
                if (!any) {
                    continue;
                }
                if (!headerSeen) {
                    headerSeen = true;
                    header = cells;
                    continue;
                }
                List<String> labeled = new ArrayList<>();
                for (int index = 0; index < cells.size(); index++) {
                    String key = index < header.size() && !header.get(index).isBlank()
                            ? header.get(index) : ("列" + (index + 1));
                    labeled.add(key + ": " + cells.get(index));
                }
                addBlock(fullText, blocks, String.join("；", labeled));
            }
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Excel 未提取到可索引文本");
        }
        return new ParsedDocument("text", fullText.toString(), null, blocks);
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

    private boolean isZip(byte[] content) {
        return content.length > 4
                && content[0] == (byte) 0x50 && content[1] == (byte) 0x4B
                && content[2] == (byte) 0x03 && content[3] == (byte) 0x04;
    }
}
