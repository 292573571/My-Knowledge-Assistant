package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.rag.DocumentChunkerRouter;
import com.example.workbench.rag.DocumentParserRouter;
import com.example.workbench.rag.PdfDocumentParser;
import com.example.workbench.rag.PdfPageChunker;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserRuleBasedEvaluatorTest {
    @Test
    void evaluatesPdfFixtureWithoutModelOrVectorStore() {
        ParserRuleBasedEvaluator evaluator = new ParserRuleBasedEvaluator(
                new DocumentParserRouter(List.of(new PdfDocumentParser(image -> ""))),
                new DocumentChunkerRouter(List.of(new PdfPageChunker())));
        EvalCase evalCase = new EvalCase("parser-test", "parser", "pdf_page", "question", false,
                false, false, List.of("quality-page.pdf"), List.of(), List.of("FIFTEEN MINUTES"), List.of(),
                List.of(2), List.of(), List.of(), EvalSuite.FORMAT, EvalLayer.PARSER, List.of(), null, null, List.of());

        EvalResult result = evaluator.evaluate(evalCase);

        assertThat(result.passed()).isTrue();
        assertThat(result.matchedKeywords()).containsExactly("FIFTEEN MINUTES");
    }
}
