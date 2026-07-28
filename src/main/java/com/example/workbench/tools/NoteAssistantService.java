package com.example.workbench.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class NoteAssistantService {

    private static final Pattern FILE_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*\\.(md|txt)");

    private final WorkspaceFileService workspaceFileService;

    public NoteAssistantService(WorkspaceFileService workspaceFileService) {
        this.workspaceFileService = workspaceFileService;
    }

    public Optional<String> handle(String message) {
        if (message.contains("总结") && extractFiles(message).length > 0) {
            return Optional.of(summarizeToFile(message));
        }

        if (message.contains("创建") && (message.contains("学习计划") || extractFiles(message).length > 0)) {
            return Optional.of(createLearningPlan(message));
        }

        return Optional.empty();
    }

    private String summarizeToFile(String message) {
        try {
            var files = extractFiles(message);

            if (files.length == 0) {
                return "请指定要总结的源文件，例如：请总结 mcp-notes.md 并写入 mcp-summary.md";
            }

            String sourceFile = files[0];
            String targetFile = files.length > 1 ? files[1] : "summary.md";
            String content = workspaceFileService.readNoteOrDoc(sourceFile);
            Path writtenPath = workspaceFileService.writeNote(targetFile, buildSummary(sourceFile, content));

            return "已总结 " + sourceFile + " 并写入 " + writtenPath;
        } catch (IOException | IllegalArgumentException exception) {
            return "处理总结请求失败：" + exception.getMessage();
        }
    }

    private String createLearningPlan(String message) {
        try {
            String targetFile = extractFiles(message).length > 0 ? extractFiles(message)[0] : "learning-plan.md";
            Path writtenPath = workspaceFileService.writeNote(targetFile, buildLearningPlan(targetFile));

            return "已创建学习计划文件：" + writtenPath;
        } catch (IOException | IllegalArgumentException exception) {
            return "创建学习计划失败：" + exception.getMessage();
        }
    }

    private String[] extractFiles(String message) {
        var matcher = FILE_PATTERN.matcher(message);
        return matcher.results()
                .map(result -> result.group())
                .toArray(String[]::new);
    }

    private String buildSummary(String sourceFile, String content) {
        String title = content.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst()
                .orElse(sourceFile);
        String keyPoints = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .limit(6)
                .map(line -> "- " + line.replaceFirst("^-\\s*", ""))
                .reduce("", (left, right) -> left + right + "\n");

        if (keyPoints.isBlank()) {
            keyPoints = "- 暂未提取到明确要点。\n";
        }

        return "# " + title + " 总结\n\n"
                + "来源文件：`" + sourceFile + "`\n\n"
                + "## 核心要点\n\n"
                + keyPoints;
    }

    private String buildLearningPlan(String targetFile) {
        return "# " + targetFile.replaceFirst("\\.md$", "") + "\n\n"
                + "## 学习目标\n\n"
                + "- 理解核心概念。\n"
                + "- 完成最小可运行示例。\n"
                + "- 总结常见问题和实践经验。\n\n"
                + "## 计划\n\n"
                + "- 第 1 天：阅读资料并整理术语。\n"
                + "- 第 2 天：实现基础 Demo。\n"
                + "- 第 3 天：补充测试和总结。\n";
    }
}
