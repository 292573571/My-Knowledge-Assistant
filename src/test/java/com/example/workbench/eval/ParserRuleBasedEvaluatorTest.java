package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.workbench.rag.CsvDocumentParser;
import com.example.workbench.rag.DocumentChunkerRouter;
import com.example.workbench.rag.DocumentParserRouter;
import com.example.workbench.rag.ExcelDocumentParser;
import com.example.workbench.rag.JsonDocumentParser;
import com.example.workbench.rag.OcrEngine;
import com.example.workbench.rag.PdfDocumentParser;
import com.example.workbench.rag.PdfPageChunker;
import com.example.workbench.rag.PowerPointDocumentParser;
import com.example.workbench.rag.PptSlideChunker;
import com.example.workbench.rag.TextParagraphChunker;
import com.example.workbench.rag.XmlDocumentParser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FORMAT 套件的确定性解析回归：不依赖数据库、向量库与模型。
 *
 * <p>覆盖 PDF/DOCX/Excel/PPT/CSV/JSON/XML 七种格式，每种都校验真实解析结果里
 * 能取到题面依赖的事实——解析器静默丢内容、分块器不识别新类型都会在这里失败。
 */
class ParserRuleBasedEvaluatorTest {

    @Test
    void evaluatesPdfFixtureWithoutModelOrVectorStore() {
        ParserRuleBasedEvaluator evaluator = new ParserRuleBasedEvaluator(
                new DocumentParserRouter(List.of(new PdfDocumentParser(image -> ""))),
                new DocumentChunkerRouter(List.of(new PdfPageChunker())));
        EvalCase evalCase = parserCase("parser-pdf", "pdf_page", "quality-page.pdf",
                List.of("FIFTEEN MINUTES"), List.of(2));

        EvalResult result = evaluator.evaluate(evalCase);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).containsExactly("FIFTEEN MINUTES");
    }

    @Test
    @DisplayName("DOCX 保留标题层级与正文事实")
    void evaluatesDocxFixture() {
        ParserRuleBasedEvaluator evaluator = new ParserRuleBasedEvaluator(
                new DocumentParserRouter(List.of(new com.example.workbench.rag.DocxDocumentParser())),
                new DocumentChunkerRouter(List.of(new com.example.workbench.rag.DocxBlockChunker())));
        EvalCase evalCase = parserCase("format-docx", "docx_heading", "quality-heading.docx",
                List.of("隔离故障节点"), List.of());

        EvalResult result = evaluator.evaluate(evalCase);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).contains("隔离故障节点");
    }

    @Test
    @DisplayName("XLSX 保留表头列名与单元格事实")
    void evaluatesExcelFixture() {
        ParserRuleBasedEvaluator evaluator = new ParserRuleBasedEvaluator(
                new DocumentParserRouter(List.of(new ExcelDocumentParser())),
                new DocumentChunkerRouter(List.of(new TextParagraphChunker())));
        EvalCase evalCase = parserCase("format-xlsx", "xlsx_table", "quality-sheet.xlsx",
                List.of("十五分钟", "交易平台组", "服务名称"), List.of());

        EvalResult result = evaluator.evaluate(evalCase);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).contains("十五分钟", "交易平台组", "服务名称");
    }

    @Test
    @DisplayName("PPTX 按幻灯片抽取正文并命中第二页事实")
    void evaluatesPowerPointFixture() {
        ParserRuleBasedEvaluator evaluator = new ParserRuleBasedEvaluator(
                new DocumentParserRouter(List.of(new PowerPointDocumentParser(
                        mock(OcrEngine.class), "never", 1600, 500, 200))),
                new DocumentChunkerRouter(List.of(new PptSlideChunker())));
        EvalCase evalCase = parserCase("format-pptx", "pptx_slide", "quality-slides.pptx",
                List.of("FIFTEEN MINUTES", "ORDER SERVICE RECOVERY"), List.of());

        EvalResult result = evaluator.evaluate(evalCase);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).contains("FIFTEEN MINUTES");
    }

    @Test
    @DisplayName("CSV 保留列名映射与字段值")
    void evaluatesCsvFixture() {
        ParserRuleBasedEvaluator evaluator = new ParserRuleBasedEvaluator(
                new DocumentParserRouter(List.of(new CsvDocumentParser())),
                new DocumentChunkerRouter(List.of(new TextParagraphChunker())));
        EvalCase evalCase = parserCase("format-csv", "csv_table", "quality-data.csv",
                List.of("recovery_target", "FIFTEEN MINUTES", "交易平台组"), List.of());

        EvalResult result = evaluator.evaluate(evalCase);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).contains("recovery_target", "FIFTEEN MINUTES");
    }

    @Test
    @DisplayName("JSON 嵌套字段摊平后可检索")
    void evaluatesJsonFixture() {
        ParserRuleBasedEvaluator evaluator = new ParserRuleBasedEvaluator(
                new DocumentParserRouter(List.of(new JsonDocumentParser())),
                new DocumentChunkerRouter(List.of(new TextParagraphChunker())));
        EvalCase evalCase = parserCase("format-json", "json_document", "quality-config.json",
                List.of("FIFTEEN MINUTES", "EVERY FOURTEEN DAYS", "critical"), List.of());

        EvalResult result = evaluator.evaluate(evalCase);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).contains("EVERY FOURTEEN DAYS");
    }

    @Test
    @DisplayName("XML 元素文本按「标签: 值」进入索引")
    void evaluatesXmlFixture() {
        ParserRuleBasedEvaluator evaluator = new ParserRuleBasedEvaluator(
                new DocumentParserRouter(List.of(new XmlDocumentParser())),
                new DocumentChunkerRouter(List.of(new TextParagraphChunker())));
        EvalCase evalCase = parserCase("format-xml", "xml_document", "quality-feed.xml",
                List.of("recoveryTarget", "FIFTEEN MINUTES", "交易平台组"), List.of());

        EvalResult result = evaluator.evaluate(evalCase);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).contains("recoveryTarget", "交易平台组");
    }

    private EvalCase parserCase(String id, String type, String fixture,
                                List<String> expectedKeywords, List<Integer> expectedPages) {
        return new EvalCase(id, "parser", type, "解析验证", false,
                false, false, List.of(fixture), List.of(), expectedKeywords, List.of(),
                expectedPages, List.of(), List.of(), EvalSuite.FORMAT, EvalLayer.PARSER,
                List.of(), null, null, List.of());
    }
}
