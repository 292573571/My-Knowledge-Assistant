package com.example.workbench.tools;

import com.example.workbench.advisor.AnswerJudge;
import com.example.workbench.advisor.ToolCallLogger;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.SourceDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SummaryWorkflowService {

    private static final Pattern FILE_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*\\.md");

    private final DocumentIngestionService documentIngestionService;
    private final RagService ragService;
    private final WorkspaceFileService workspaceFileService;
    private final ToolCallLogger toolCallLogger;
    private final AnswerJudge answerJudge;

    public SummaryWorkflowService(
            DocumentIngestionService documentIngestionService,
            RagService ragService,
            WorkspaceFileService workspaceFileService,
            ToolCallLogger toolCallLogger,
            AnswerJudge answerJudge
    ) {
        this.documentIngestionService = documentIngestionService;
        this.ragService = ragService;
        this.workspaceFileService = workspaceFileService;
        this.toolCallLogger = toolCallLogger;
        this.answerJudge = answerJudge;
    }

    public String summarizeAndWrite(String message) {
        try {
            String query = extractQuery(message);
            String targetFile = extractTargetFile(message);

            toolCallLogger.logToolCall("RAG", "用户要求总结知识库内容，需要先检索相关文档", 0.92);
            documentIngestionService.ingestDocsDirectory();
            List<SourceDocument> sources = ragService.retrieve(query, 5);

            if (sources.isEmpty()) {
                return "未在知识库中找到可总结的相关内容。";
            }

            String summary = buildSummary(query, sources);
            List<String> contexts = sources.stream().map(SourceDocument::content).toList();

            toolCallLogger.logToolCall("judge", "检查总结是否基于检索上下文", 0.86);
            if (!answerJudge.isGrounded(summary, contexts)) {
                summary = rebuildGroundedSummary(query, sources);
            }

            toolCallLogger.logToolCall("MCP filesystem", "用户要求把总结写成文件，只允许写入 notes 目录", 0.97);
            Path path = workspaceFileService.writeNote(targetFile, summary);

            return "已识别为文件生成任务，完成 RAG 检索、总结生成和 filesystem 写入。文件路径：" + path;
        } catch (IOException | IllegalArgumentException exception) {
            return "执行总结写入失败：" + exception.getMessage();
        }
    }

    private String extractQuery(String message) {
        if (message.toLowerCase().contains("mcp")) {
            return "MCP";
        }

        return message;
    }

    private String extractTargetFile(String message) {
        var matcher = FILE_PATTERN.matcher(message);
        String target = "summary.md";

        while (matcher.find()) {
            target = matcher.group();
        }

        return target;
    }

    private String buildSummary(String query, List<SourceDocument> sources) {
        String sourceList = sources.stream()
                .map(source -> "- `" + source.source() + "#chunk-" + source.chunkIndex() + "`")
                .distinct()
                .collect(Collectors.joining("\n"));
        String keyPoints = sources.stream()
                .map(SourceDocument::content)
                .flatMap(content -> content.lines().map(String::trim))
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .limit(8)
                .map(line -> "- " + line.replaceFirst("^-\\s*", ""))
                .collect(Collectors.joining("\n"));

        return "# " + query + " Overview\n\n"
                + "## 来源\n\n"
                + sourceList
                + "\n\n## 总结\n\n"
                + keyPoints
                + "\n";
    }

    private String rebuildGroundedSummary(String query, List<SourceDocument> sources) {
        String context = sources.stream()
                .map(SourceDocument::content)
                .collect(Collectors.joining("\n\n"));

        return "# " + query + " Overview\n\n"
                + "## 总结\n\n"
                + context
                + "\n";
    }
}
