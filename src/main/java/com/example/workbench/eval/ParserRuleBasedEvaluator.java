package com.example.workbench.eval;

import com.example.workbench.rag.DocumentChunk;
import com.example.workbench.rag.DocumentChunkerRouter;
import com.example.workbench.rag.DocumentParserRouter;
import com.example.workbench.rag.ParsedDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 在不调用模型和向量库的情况下评估文档解析及分块结果。
 */
@Component
public class ParserRuleBasedEvaluator {
    private static final Path FIXTURE_ROOT = Path.of("eval", "multiformat", "fixtures").toAbsolutePath().normalize();

    private final DocumentParserRouter parserRouter;
    private final DocumentChunkerRouter chunkerRouter;

    public ParserRuleBasedEvaluator(DocumentParserRouter parserRouter, DocumentChunkerRouter chunkerRouter) {
        this.parserRouter = parserRouter;
        this.chunkerRouter = chunkerRouter;
    }

    /**
     * 解析评测 fixture，并根据已有期望字段检查正文、页码、标题路径和关键词。
     *
     * @param evalCase 解析评测用例
     * @return 统一评测结果
     */
    public EvalResult evaluate(EvalCase evalCase) {
        Path path = fixture(evalCase);
        try {
            ParsedDocument document = parserRouter.parse(path.getFileName().toString(), Files.readAllBytes(path));
            List<DocumentChunk> chunks = chunkerRouter.select(document).chunk(document);
            String content = document.content() == null ? "" : document.content();
            List<String> matched = evalCase.expectedKeywords().stream()
                    .filter(keyword -> containsIgnoreCase(content, keyword)
                            || chunks.stream().anyMatch(chunk -> containsIgnoreCase(chunk.content(), keyword)))
                    .toList();
            List<String> missing = evalCase.expectedKeywords().stream().filter(item -> !matched.contains(item)).toList();
            boolean passed = missing.isEmpty()
                    && evalCase.expectedHeadingPaths().stream().allMatch(expected -> chunks.stream()
                    .anyMatch(chunk -> containsIgnoreCase(chunk.headingPath(), expected)))
                    && evalCase.expectedPageNumbers().stream().allMatch(expected -> chunks.stream()
                    .anyMatch(chunk -> chunk.pageNumber() == expected));
            return result(evalCase, passed, "parsed=" + document.documentType() + ", chunks=" + chunks.size(),
                    matched, missing);
        } catch (IOException | RuntimeException exception) {
            return result(evalCase, false, "parser error: " + exception.getMessage(), List.of(), evalCase.expectedKeywords());
        }
    }

    private Path fixture(EvalCase evalCase) {
        String name = evalCase.expectedSources().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PARSER case needs expectedSources fixture"));
        Path path = FIXTURE_ROOT.resolve(Path.of(name).getFileName().toString()).normalize();
        if (!path.startsWith(FIXTURE_ROOT)) {
            throw new IllegalArgumentException("Invalid parser fixture path");
        }
        return path;
    }

    private boolean containsIgnoreCase(String text, String expected) {
        return text != null && expected != null && text.toLowerCase().contains(expected.toLowerCase());
    }

    private EvalResult result(EvalCase evalCase, boolean passed, String reason, List<String> matched, List<String> missing) {
        return new EvalResult(evalCase.id(), evalCase.mode(), evalCase.type(), evalCase.question(), false,
                false, false, passed, passed ? 1 : 0, null, true, passed ? 1 : 0, reason, "Not applicable",
                "", List.of(), List.of(), evalCase.expectedHeadingPaths(), List.of(), matched, missing,
                false, false, evalCase.expectedKeywords().isEmpty() ? 1 : (double) matched.size() / evalCase.expectedKeywords().size(),
                false, false, false, List.of(), evalCase.suite(), evalCase.layer(), false, 0, 0, 0, 0, null);
    }
}
