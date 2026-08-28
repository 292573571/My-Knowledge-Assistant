package com.example.workbench.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExpandedDocumentParserTest {

    private final ExcelDocumentParser excel = new ExcelDocumentParser();
    private final PowerPointDocumentParser powerPoint = new PowerPointDocumentParser();
    private final CsvDocumentParser csv = new CsvDocumentParser();
    private final JsonDocumentParser json = new JsonDocumentParser();
    private final XmlDocumentParser xml = new XmlDocumentParser();
    private final RtfDocumentParser rtf = new RtfDocumentParser();
    private final OdtDocumentParser odt = new OdtDocumentParser();

    @Test
    void parsesXlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("员工");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("年龄");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("张三");
            row.createCell(1).setCellValue(30);
            byte[] bytes = write(workbook);
            ParsedDocument parsed = excel.parse(bytes);
            assertTrue(parsed.content().contains("员工"));
            assertTrue(parsed.content().contains("姓名: 张三"));
            assertTrue(parsed.content().contains("年龄: 30"));
        }
    }

    @Test
    void parsesXls() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("库存");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("商品");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("键盘");
            byte[] bytes = write(workbook);
            ParsedDocument parsed = excel.parse(bytes);
            assertTrue(parsed.content().contains("库存"));
            assertTrue(parsed.content().contains("商品: 键盘"));
        }
    }

    @Test
    void parsesPptx() throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow()) {
            XSLFSlide slide = presentation.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            textBox.setText("季度复盘");
            byte[] bytes = write(presentation);
            ParsedDocument parsed = powerPoint.parse(bytes);
            assertTrue(parsed.content().contains("季度复盘"));
        }
    }

    @Test
    void parsesPpt() throws Exception {
        try (HSLFSlideShow presentation = new HSLFSlideShow()) {
            HSLFSlide slide = presentation.createSlide();
            HSLFTextBox textBox = new HSLFTextBox();
            textBox.setText("旧版幻灯片");
            slide.addShape(textBox);
            byte[] bytes = write(presentation);
            ParsedDocument parsed = powerPoint.parse(bytes);
            assertTrue(parsed.content().contains("旧版幻灯片"));
        }
    }

    @Test
    void parsesCsv() {
        String csvText = "姓名,年龄\n张三,30\n李四,25\n";
        ParsedDocument parsed = csv.parse(csvText.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.content().contains("姓名: 张三"));
        assertTrue(parsed.content().contains("年龄: 25"));
    }

    @Test
    void parsesCsvWithQuotedCommas() {
        String csvText = "城市,描述\n\"北京,上海\",\"两个直辖市\"\n";
        ParsedDocument parsed = csv.parse(csvText.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.content().contains("城市: 北京,上海"));
    }

    @Test
    void parsesJson() {
        String jsonText = "{\"name\":\"张三\",\"age\":30,\"skills\":[\"Java\",\"Go\"]}";
        ParsedDocument parsed = json.parse(jsonText.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.content().contains("name: 张三"));
        assertTrue(parsed.content().contains("skills[0]: Java"));
    }

    @Test
    void parsesXml() {
        String xmlText = "<root><item>苹果</item><item>香蕉</item></root>";
        ParsedDocument parsed = xml.parse(xmlText.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.content().contains("item: 苹果"));
        assertTrue(parsed.content().contains("item: 香蕉"));
    }

    @Test
    void parsesRtf() {
        String rtfText = "{\\rtf1\\ansi{\\fonttbl{f0\\fnil Arial;}}{\\pard Hello RTF line one\\par second line\\par}}";
        ParsedDocument parsed = rtf.parse(rtfText.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.content().contains("Hello RTF line one"));
        assertTrue(parsed.content().contains("second line"));
    }

    @Test
    void parsesOdt() throws Exception {
        String contentXml = "<office:document-content xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
                + "<text:p>第一节内容</text:p><text:p>第二节内容</text:p></office:document-content>";
        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipOut)) {
            ZipEntry entry = new ZipEntry("content.xml");
            zos.putNextEntry(entry);
            zos.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        ParsedDocument parsed = odt.parse(zipOut.toByteArray());
        assertTrue(parsed.content().contains("第一节内容"));
        assertTrue(parsed.content().contains("第二节内容"));
    }

    @Test
    void rejectsEmptyExcel() {
        assertThrows(IllegalArgumentException.class, () -> excel.parse(new byte[0]));
    }

    @Test
    void whitelistAllowsExpandedTypes() {
        var resolver = new IngestionPathResolver(java.nio.file.Path.of("docs"));
        assertTrue(resolver.isSupportedDocument(java.nio.file.Path.of("a.xlsx")));
        assertTrue(resolver.isSupportedDocument(java.nio.file.Path.of("a.ppt")));
        assertTrue(resolver.isSupportedDocument(java.nio.file.Path.of("a.csv")));
        assertTrue(resolver.isSupportedDocument(java.nio.file.Path.of("a.json")));
        assertTrue(resolver.isSupportedDocument(java.nio.file.Path.of("a.xml")));
        assertTrue(resolver.isSupportedDocument(java.nio.file.Path.of("a.doc")));
        assertTrue(resolver.isSupportedDocument(java.nio.file.Path.of("a.rtf")));
        assertTrue(resolver.isSupportedDocument(java.nio.file.Path.of("a.odt")));
        assertFalse(resolver.isSupportedDocument(java.nio.file.Path.of("a.exe")));
    }

    private byte[] write(XSSFWorkbook document) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.write(out);
        return out.toByteArray();
    }

    private byte[] write(HSSFWorkbook document) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.write(out);
        return out.toByteArray();
    }

    private byte[] write(XMLSlideShow document) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.write(out);
        return out.toByteArray();
    }

    private byte[] write(HWPFDocument document) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.write(out);
        return out.toByteArray();
    }

    private byte[] write(HSLFSlideShow document) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.write(out);
        return out.toByteArray();
    }
}
