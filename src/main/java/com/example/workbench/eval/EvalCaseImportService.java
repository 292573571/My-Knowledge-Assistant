package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvalCaseImportService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private final EvalCaseService evalCaseService;
    private final EvalImportStorage importStorage;
    private final ObjectMapper objectMapper;

    public EvalCaseImportService(EvalCaseService evalCaseService, EvalImportStorage importStorage, ObjectMapper objectMapper) {
        this.evalCaseService = evalCaseService;
        this.importStorage = importStorage;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EvalCaseImportResponse importCases(AppUser user, MultipartFile file) {
        validate(file);
        List<EvalCaseRequest> cases = parse(file);
        if (cases.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件中没有评测题");
        List<EvalCaseRequest> normalizedCases = cases.stream().map(this::normalize).toList();
        normalizedCases.forEach(item -> evalCaseService.create(user, item));
        try {
            importStorage.save(user, file.getOriginalFilename(), file.getContentType(), file.getBytes(), normalizedCases.size());
            return new EvalCaseImportResponse(normalizedCases.size());
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存导入文件", exception);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择导入文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件不能超过 5 MB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".xlsx") || name.endsWith(".md") || name.endsWith(".json"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 .xlsx、.md 或 .json 文件");
        }
    }

    private List<EvalCaseRequest> parse(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".xlsx")) return parseExcel(file.getBytes());
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return name.endsWith(".json") ? parseJson(content) : parseMarkdown(content);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取导入文件");
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException) throw exception;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件格式无效");
        }
    }

    private List<EvalCaseRequest> parseJson(String content) throws IOException {
        String trimmed = content.trim();
        if (trimmed.startsWith("[")) return objectMapper.readValue(trimmed, new TypeReference<List<EvalCaseRequest>>() { });
        List<EvalCaseRequest> cases = new ArrayList<>();
        for (String line : trimmed.split("\\R")) if (!line.isBlank()) cases.add(objectMapper.readValue(line, EvalCaseRequest.class));
        return cases;
    }

    private List<EvalCaseRequest> parseExcel(byte[] data) {
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(data))) {
            Map<String, String> entries = new LinkedHashMap<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            List<String> sharedStrings = xmlTexts(entries.get("xl/sharedStrings.xml"));
            String sheet = entries.entrySet().stream().filter(item -> item.getKey().matches("xl/worksheets/sheet\\d+\\.xml")).map(Map.Entry::getValue).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Excel 中未找到工作表"));
            List<Map<Integer, String>> rows = xlsxRows(sheet, sharedStrings);
            if (rows.size() < 2) return List.of();
            Map<String, Integer> headers = new LinkedHashMap<>();
            rows.get(0).forEach((index, value) -> headers.put(key(value), index));
            List<EvalCaseRequest> cases = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                Map<String, String> values = new LinkedHashMap<>();
                Map<Integer, String> row = rows.get(rowIndex);
                headers.forEach((name, index) -> values.put(name, row.getOrDefault(index, "")));
                if (values.values().stream().anyMatch(value -> !value.isBlank())) cases.add(fromValues(values));
            }
            return cases;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Excel 文件格式无效");
        }
    }

    private List<Map<Integer, String>> xlsxRows(String sheetXml, List<String> sharedStrings) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        var rowMatcher = java.util.regex.Pattern.compile("<row[^>]*>(.*?)</row>", java.util.regex.Pattern.DOTALL).matcher(sheetXml);
        while (rowMatcher.find()) {
            Map<Integer, String> row = new LinkedHashMap<>();
            var cellMatcher = java.util.regex.Pattern.compile("<c\\s+([^>]*)>(.*?)</c>", java.util.regex.Pattern.DOTALL).matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                String attributes = cellMatcher.group(1);
                var reference = java.util.regex.Pattern.compile("r=\"([A-Z]+)\\d+\"").matcher(attributes);
                if (!reference.find()) continue;
                var type = java.util.regex.Pattern.compile("t=\"([^\"]+)\"").matcher(attributes);
                String raw = xmlTexts(cellMatcher.group(2)).stream().findFirst().orElse("");
                String value = type.find() && "s".equals(type.group(1)) && raw.matches("\\d+") ? sharedStrings.get(Integer.parseInt(raw)) : raw;
                row.put(columnIndex(reference.group(1)), value);
            }
            rows.add(row);
        }
        return rows;
    }

    private List<String> xmlTexts(String xml) {
        if (xml == null) return List.of();
        List<String> texts = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("<t[^>]*>(.*?)</t>|<v[^>]*>(.*?)</v>", java.util.regex.Pattern.DOTALL).matcher(xml);
        while (matcher.find()) texts.add(unescape(matcher.group(1) == null ? matcher.group(2) : matcher.group(1)));
        return texts;
    }

    private int columnIndex(String column) { int index = 0; for (char character : column.toCharArray()) index = index * 26 + character - 'A' + 1; return index - 1; }
    private String unescape(String value) { return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""); }

    private List<EvalCaseRequest> parseMarkdown(String content) {
        List<String> rows = content.lines().map(String::trim).filter(line -> line.startsWith("|")).toList();
        if (rows.size() < 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Markdown 需包含表头和至少一条题目");
        String[] headerCells = cells(rows.get(0));
        List<EvalCaseRequest> cases = new ArrayList<>();
        for (int index = 2; index < rows.size(); index++) {
            String[] cells = cells(rows.get(index));
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < Math.min(headerCells.length, cells.length); column++) values.put(key(headerCells[column]), cells[column]);
            if (values.values().stream().anyMatch(value -> !value.isBlank())) cases.add(fromValues(values));
        }
        return cases;
    }

    private String[] cells(String row) {
        return row.replaceFirst("^\\|", "").replaceFirst("\\|$", "").split("\\|", -1);
    }

    private EvalCaseRequest fromValues(Map<String, String> values) {
        return new EvalCaseRequest(value(values, "caseid", "case_id", "编号"), value(values, "mode", "模式"),
                value(values, "type", "类型"), value(values, "question", "问题", "评测问题"), bool(values, "expectnoanswer", "expect_no_answer", "期望无回答"),
                bool(values, "requirelocalevidence", "require_local_evidence", "要求本地证据"), bool(values, "allowmodelfallback", "allow_model_fallback", "允许模型兜底"),
                list(value(values, "expectedsources", "expected_sources", "期望来源")), list(value(values, "expectedheadingpaths", "expected_heading_paths", "期望标题路径")),
                list(value(values, "expectedkeywords", "expected_keywords", "期望关键词")), list(value(values, "forbiddenkeywords", "forbidden_keywords", "禁用关键词")),
                integers(value(values, "expectedpagenumbers", "expected_page_numbers", "期望页码")),
                list(value(values, "expectedretrievalkeywords", "expected_retrieval_keywords", "期望检索关键词")),
                list(value(values, "forbiddenretrievalkeywords", "forbidden_retrieval_keywords", "禁用检索关键词")));
    }

    private EvalCaseRequest normalize(EvalCaseRequest request) {
        if (request.question() == null || request.question().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每条导入题目都必须填写问题");
        return new EvalCaseRequest(null, request.mode(), request.type() == null || request.type().isBlank() ? "fact" : request.type(), request.question().trim(),
                request.expectNoAnswer(), request.requireLocalEvidence(), request.allowModelFallback(), request.expectedSources(), request.expectedHeadingPaths(), request.expectedKeywords(), request.forbiddenKeywords(),
                request.expectedPageNumbers(), request.expectedRetrievalKeywords(), request.forbiddenRetrievalKeywords());
    }

    private String key(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(" ", ""); }
    private String value(Map<String, String> values, String... keys) { for (String key : keys) if (values.containsKey(key(key))) return values.get(key(key)); return ""; }
    private boolean bool(Map<String, String> values, String... keys) { String value = value(values, keys); return List.of("true", "1", "yes", "是").contains(value.trim().toLowerCase(Locale.ROOT)); }
    private List<String> list(String value) { return value == null || value.isBlank() ? List.of() : List.of(value.split("[，,、\\n]")) .stream().map(String::trim).filter(item -> !item.isBlank()).toList(); }
    private List<Integer> integers(String value) { return list(value).stream().map(item -> { try { return Integer.parseInt(item); } catch (NumberFormatException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "期望页码必须是整数"); } }).toList(); }
}
