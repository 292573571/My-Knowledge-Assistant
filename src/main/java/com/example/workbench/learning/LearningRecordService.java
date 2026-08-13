package com.example.workbench.learning;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.RagSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LearningRecordService {

    private static final Logger log = LoggerFactory.getLogger(LearningRecordService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Path RECORDS_DIRECTORY = Path.of("docs", "learning-records");
    private static final Path NOTES_DIRECTORY = Path.of("docs", "manual-notes");
    private static final Pattern QUESTION_ANSWER_ENTRY = Pattern.compile(
            "(?ms)^## 问题[ \\t]*\\R+(.*?)\\R+^## 回答[ \\t]*\\R+.*?(?=^## 问题[ \\t]*$|\\z)"
    );
    private static final Pattern TEACHING_CHECK_ENTRY = Pattern.compile(
            "(?ms)^## 教学检查[ \\t]*\\R+(.*?)(?=^## 教学检查[ \\t]*$|\\z)"
    );
    private static final Pattern TEACHING_TOPIC = Pattern.compile("(?m)^- 主题：(.+)$");
    private static final Pattern TEACHING_WORKSPACE = Pattern.compile("(?m)^- 知识空间：(.+)$");
    private static final Pattern TEACHING_SCORE = Pattern.compile("(?m)^- 得分：(\\d+)/(\\d+)$");
    private static final Pattern TEACHING_RESULT = Pattern.compile("(?m)^- 结果：(通过|需要复习)$");
    private static final Pattern RECORD_ENTRY = Pattern.compile(
            "(?ms)^## (?:问题|教学检查)[ \\t]*\\R+.*?(?=^## (?:问题|教学检查)[ \\t]*$|\\z)");
    private static final Pattern WORKSPACE_MARKER = Pattern.compile("(?m)^- 知识空间：(.+)$");
    private static final List<String> UNRELIABLE_ANSWER_MARKERS = List.of(
            "我在当前知识库中没有找到足够信息和依据来回答这个问题",
            "当前知识库没有足够信息回答该问题",
            "当前知识库没有包含任何信息",
            "当前知识库中没有包含任何信息",
            "当前知识库没有任何信息",
            "当前知识库为空",
            "当前无法生成可靠的通用知识回答",
            "请求失败，请稍后重试",
            "暂时无法回答",
            "无法回答这个问题"
    );

    private final DocumentIngestionService documentIngestionService;
    private final Clock clock;
    private final Path notesDirectory;

    @Autowired
    public LearningRecordService(DocumentIngestionService documentIngestionService) {
        this(documentIngestionService, Clock.systemDefaultZone(), RECORDS_DIRECTORY, NOTES_DIRECTORY);
    }

    LearningRecordService(DocumentIngestionService documentIngestionService, Clock clock, Path recordsDirectory) {
        this(documentIngestionService, clock, recordsDirectory, recordsDirectory.resolveSibling("manual-notes"));
    }

    LearningRecordService(DocumentIngestionService documentIngestionService, Clock clock, Path recordsDirectory, Path notesDirectory) {
        this.documentIngestionService = documentIngestionService;
        this.clock = clock;
        this.recordsDirectory = recordsDirectory;
        this.notesDirectory = notesDirectory;
    }

    private final Path recordsDirectory;

    public synchronized void record(AppUser user, String question, String answer, List<RagSource> sources) {
        record(user, null, question, answer, sources);
    }

    public synchronized void record(AppUser user, String workspaceId, String question, String answer,
                                    List<RagSource> sources) {
        if (question == null || question.isBlank() || !isRecordableAnswer(answer)) {
            log.info("Learning record skipped userId={} reason=no_reliable_answer", user.getId());
            return;
        }

        Path record = recordPath(user);
        try {
            Files.createDirectories(record.getParent());
            if (!Files.exists(record)) {
                Files.writeString(record, "# " + LocalDate.now(clock) + " 学习记录\n", StandardCharsets.UTF_8);
            }
            String entry = formatEntry(workspaceId, question, answer);
            String existingContent = Files.readString(record, StandardCharsets.UTF_8);
            String updatedContent = replaceAnswerForQuestion(existingContent, workspaceId, question, entry);
            if (updatedContent != null) {
                // 同一问题保留最新的有效回答，修正模型切换后产生的旧答案，同时避免重复条目。
                Files.writeString(record, updatedContent, StandardCharsets.UTF_8);
            } else {
                Files.writeString(record, entry, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException exception) {
            // Learning notes must not make an otherwise successful chat request fail.
            log.warn("Learning record could not be saved userId={} errorType={}", user.getId(), exception.getClass().getSimpleName());
        }
    }

    public synchronized String recordTeachingCheck(AppUser user, String attemptId, String topic,
                                                   String question, String answer, int score, int maxScore,
                                                   boolean passed, String feedback, String weakPoint,
                                                   String reviewExplanation, String reviewSuggestion) {
        return recordTeachingCheck(user, null, attemptId, topic, question, answer, score, maxScore, passed,
                feedback, weakPoint, reviewExplanation, reviewSuggestion);
    }

    public synchronized String recordTeachingCheck(AppUser user, String workspaceId, String attemptId, String topic,
                                                   String question, String answer, int score, int maxScore,
                                                   boolean passed, String feedback, String weakPoint,
                                                   String reviewExplanation, String reviewSuggestion) {
        Path record = recordPath(user);
        try {
            Files.createDirectories(record.getParent());
            if (!Files.exists(record)) {
                Files.writeString(record, "# " + LocalDate.now(clock) + " 学习记录\n", StandardCharsets.UTF_8);
            }
            String existingContent = Files.readString(record, StandardCharsets.UTF_8);
            if (existingContent.contains("attemptId：" + attemptId)) {
                return DATE_FORMAT.format(LocalDate.now(clock));
            }
            String entry = formatTeachingCheck(workspaceId, attemptId, topic, question, answer, score, maxScore, passed,
                    feedback, weakPoint, reviewExplanation, reviewSuggestion);
            Files.writeString(record, entry, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            return DATE_FORMAT.format(LocalDate.now(clock));
        } catch (IOException exception) {
            log.warn("Teaching check record could not be saved userId={} errorType={}", user.getId(), exception.getClass().getSimpleName());
            throw new IllegalStateException("Failed to save teaching check record", exception);
        }
    }

    public List<LearningRecordSummary> list(AppUser user) {
        return list(user, null);
    }

    public List<LearningRecordSummary> list(AppUser user, String workspaceId) {
        Path directory = recordsDirectory.resolve(userDirectory(user));
        if (!Files.exists(directory)) {
            return List.of();
        }

        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .filter(path -> workspaceId == null || hasWorkspaceEntry(path, workspaceId))
                    .map(this::summary)
                    .sorted(Comparator.comparing(LearningRecordSummary::date).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list learning records", exception);
        }
    }

    public List<TeachingTopicProgress> teachingProgress(AppUser user) {
        return teachingProgress(user, null);
    }

    public List<TeachingTopicProgress> teachingProgress(AppUser user, String workspaceId) {
        Path directory = recordsDirectory.resolve(userDirectory(user));
        if (!Files.exists(directory)) return List.of();

        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .flatMap(path -> teachingChecks(path).stream()
                            .filter(check -> workspaceId == null || workspaceId.equals(check.workspaceId())))
                    .collect(java.util.stream.Collectors.groupingBy(TeachingCheckSnapshot::topicKey,
                            java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()))
                    .entrySet().stream()
                    .map(entry -> progress(entry.getValue()))
                    .sorted(Comparator.comparing(TeachingTopicProgress::latestDate).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read teaching progress", exception);
        }
    }

    public LearningRecordDetail detail(AppUser user, String date) {
        return detail(user, null, date);
    }

    public LearningRecordDetail detail(AppUser user, String workspaceId, String date) {
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习记录不存在");
        }

        Path record = recordsDirectory.resolve(userDirectory(user)).resolve(date + ".md").normalize();
        if (!record.startsWith(recordsDirectory) || !Files.isRegularFile(record)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习记录不存在");
        }

        try {
            String content = Files.readString(record, StandardCharsets.UTF_8);
            if (workspaceId != null) {
                content = scopedContent(content, workspaceId);
                if (!hasEntry(content)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习记录不存在");
            }
            return new LearningRecordDetail(date, date + " 学习记录", content, Files.getLastModifiedTime(record).toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read learning record", exception);
        }
    }

    public synchronized LearningRecordDetail update(AppUser user, String date, String content) {
        return update(user, null, date, content);
    }

    public synchronized LearningRecordDetail update(AppUser user, String workspaceId, String date, String content) {
        Path record = requireRecord(user, date);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学习记录内容不能为空");
        }
        if (content.length() > 200_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学习记录内容过长");
        }

        try {
            String nextContent = content.strip() + "\n";
            if (workspaceId != null) {
                nextContent = replaceWorkspaceContent(Files.readString(record, StandardCharsets.UTF_8), nextContent, workspaceId);
            }
            Files.writeString(record, nextContent, StandardCharsets.UTF_8);
            return detail(user, workspaceId, date);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to update learning record", exception);
        }
    }

    public synchronized void delete(AppUser user, String date) {
        delete(user, null, date);
    }

    public synchronized void delete(AppUser user, String workspaceId, String date) {
        Path record = requireRecord(user, date);
        documentIngestionService.deleteIndexedPath(workspacePath(record));
        try {
            if (workspaceId == null) {
                Files.delete(record);
            } else {
                Files.writeString(record, removeWorkspaceEntries(Files.readString(record, StandardCharsets.UTF_8), workspaceId), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete learning record", exception);
        }
    }

    public synchronized FormalNoteResult promote(AppUser user, String date) {
        return promote(user, date, null);
    }

    public synchronized FormalNoteResult promote(AppUser user, String date, String editedContent) {
        return promote(user, null, date, editedContent);
    }

    public synchronized FormalNoteResult promote(AppUser user, String workspaceId, String date, String editedContent) {
        Path record = requireRecord(user, date);
        Path note = notesDirectory.resolve(userDirectory(user))
                .resolve(workspaceId == null ? "" : safeWorkspaceDirectory(workspaceId))
                .resolve(date + "-learning-note.md").normalize();
        if (!note.startsWith(notesDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正式笔记路径无效");
        }

        try {
            String recordContent = editedContent == null ? Files.readString(record, StandardCharsets.UTF_8) : validateContent(editedContent);
            if (workspaceId != null) {
                recordContent = scopedContent(recordContent, workspaceId);
                if (!hasEntry(recordContent)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习记录不存在");
                }
            }
            Files.createDirectories(note.getParent());
            String body = withoutGeneratedTitle(date, recordContent);
            String noteContent = "# " + date + " 正式笔记\n\n" + body.strip() + "\n";
            String learningRecordContent = "# " + date + " 学习记录\n\n" + body.strip() + "\n";
            Files.writeString(note, noteContent, StandardCharsets.UTF_8);
            // 同一学习日期反复提升只更新同一份正式笔记，先清理历史索引避免旧版本重复显示。
            documentIngestionService.deleteIndexedPath(workspacePath(note));
            documentIngestionService.ingestDocument(note.toString(), true);
            // 两类文档共享正文，但保留各自的标题与语义，学习记录不参与知识库索引。
            if (workspaceId == null) {
                Files.writeString(record, learningRecordContent, StandardCharsets.UTF_8);
            } else {
                Files.writeString(record, replaceWorkspaceContent(Files.readString(record, StandardCharsets.UTF_8), learningRecordContent, workspaceId), StandardCharsets.UTF_8);
            }
            documentIngestionService.deleteIndexedPath(workspacePath(record));
            return new FormalNoteResult(note.getFileName().toString(), workspacePath(note));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create formal note", exception);
        }
    }

    private String validateContent(String content) {
        if (content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学习记录内容不能为空");
        }
        if (content.length() > 200_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学习记录内容过长");
        }
        return content;
    }

    private String withoutGeneratedTitle(String date, String content) {
        String formalHeader = "# " + date + " 正式笔记";
        String learningHeader = "# " + date + " 学习记录";
        String normalized = content.stripLeading();

        while (normalized.startsWith(formalHeader) || normalized.startsWith(learningHeader)) {
            String header = normalized.startsWith(formalHeader) ? formalHeader : learningHeader;
            normalized = normalized.substring(header.length()).stripLeading();
        }

        return normalized;
    }

    private Path recordPath(AppUser user) {
        return recordsDirectory.resolve(userDirectory(user)).resolve(DATE_FORMAT.format(LocalDate.now(clock)) + ".md");
    }

    private Path requireRecord(AppUser user, String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习记录不存在");
        }
        Path record = recordsDirectory.resolve(userDirectory(user)).resolve(date + ".md").normalize();
        if (!record.startsWith(recordsDirectory) || !Files.isRegularFile(record)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习记录不存在");
        }
        return record;
    }

    private String workspacePath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path workspace = Path.of("").toAbsolutePath().normalize();
        return absolute.startsWith(workspace) ? workspace.relativize(absolute).toString().replace('\\', '/') : path.toString();
    }

    private String userDirectory(AppUser user) {
        return "user-" + (user.getId() == null ? user.getAccount() : user.getId());
    }

    private LearningRecordSummary summary(Path path) {
        try {
            String fileName = path.getFileName().toString();
            String date = fileName.substring(0, fileName.length() - ".md".length());
            return new LearningRecordSummary(date, date + " 学习记录", Files.getLastModifiedTime(path).toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read learning record metadata", exception);
        }
    }

    private List<TeachingCheckSnapshot> teachingChecks(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String date = path.getFileName().toString().replaceFirst("\\.md$", "");
            List<TeachingCheckSnapshot> checks = new java.util.ArrayList<>();
            Matcher entries = TEACHING_CHECK_ENTRY.matcher(content);
            while (entries.find()) {
                Matcher topic = TEACHING_TOPIC.matcher(entries.group(1));
                Matcher workspace = TEACHING_WORKSPACE.matcher(entries.group(1));
                Matcher score = TEACHING_SCORE.matcher(entries.group(1));
                Matcher result = TEACHING_RESULT.matcher(entries.group(1));
                if (topic.find() && score.find() && result.find()) {
                    String displayTopic = TeachingTopicNormalizer.display(topic.group(1));
                    checks.add(new TeachingCheckSnapshot(displayTopic, TeachingTopicNormalizer.key(displayTopic),
                            workspace.find() ? workspace.group(1).strip() : null,
                            Integer.parseInt(score.group(1)), Integer.parseInt(score.group(2)),
                            "通过".equals(result.group(1)), date));
                }
            }
            return checks;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read teaching progress entry", exception);
        }
    }

    private TeachingTopicProgress progress(List<TeachingCheckSnapshot> checks) {
        // 同一天可能有多次检查，文件中的追加顺序比日期更能表示最近一次结果。
        TeachingCheckSnapshot latest = checks.get(checks.size() - 1);
        int bestScore = checks.stream().mapToInt(TeachingCheckSnapshot::score).max().orElse(0);
        int maxScore = checks.stream().mapToInt(TeachingCheckSnapshot::maxScore).max().orElse(5);
        int passed = (int) checks.stream().filter(TeachingCheckSnapshot::passed).count();
        return new TeachingTopicProgress(latest.topic(), checks.size(), passed, bestScore, maxScore,
                latest.score(), latest.passed(), latest.date(), bestScore * 100 / maxScore);
    }

    private record TeachingCheckSnapshot(String topic, String topicKey, String workspaceId,
                                         int score, int maxScore,
                                         boolean passed, String date) {
    }

    private String formatEntry(String workspaceId, String question, String answer) {
        return "\n## 问题\n\n"
                + (workspaceId == null || workspaceId.isBlank() ? "" : "- 知识空间：" + singleLine(workspaceId) + "\n\n")
                + question.strip()
                + "\n\n## 回答\n\n"
                + withoutReferences(answer)
                + "\n";
    }

    private boolean hasWorkspaceEntry(Path path, String workspaceId) {
        try {
            return hasEntry(scopedContent(Files.readString(path, StandardCharsets.UTF_8), workspaceId));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to filter learning record", exception);
        }
    }

    private boolean hasEntry(String content) {
        return RECORD_ENTRY.matcher(content).find();
    }

    private String scopedContent(String content, String workspaceId) {
        StringBuilder result = new StringBuilder(content.substring(0, firstEntryStart(content)));
        Matcher entries = RECORD_ENTRY.matcher(content);
        while (entries.find()) {
            Matcher marker = WORKSPACE_MARKER.matcher(entries.group());
            if (marker.find() && workspaceId.equals(marker.group(1).strip())) result.append(entries.group());
        }
        return result.toString();
    }

    private String removeWorkspaceEntries(String content, String workspaceId) {
        StringBuilder result = new StringBuilder(content.substring(0, firstEntryStart(content)));
        Matcher entries = RECORD_ENTRY.matcher(content);
        while (entries.find()) {
            Matcher marker = WORKSPACE_MARKER.matcher(entries.group());
            if (!marker.find() || !workspaceId.equals(marker.group(1).strip())) result.append(entries.group());
        }
        return result.toString();
    }

    private String replaceWorkspaceContent(String existing, String replacement, String workspaceId) {
        StringBuilder result = new StringBuilder(removeWorkspaceEntries(existing, workspaceId).stripTrailing());
        Matcher entries = RECORD_ENTRY.matcher(replacement);
        while (entries.find()) result.append("\n").append(withWorkspaceMarker(entries.group(), workspaceId)).append("\n");
        return result.append('\n').toString();
    }

    private String withWorkspaceMarker(String entry, String workspaceId) {
        String normalized = WORKSPACE_MARKER.matcher(entry).replaceAll("").strip();
        int headingEnd = normalized.indexOf('\n');
        String heading = headingEnd < 0 ? normalized : normalized.substring(0, headingEnd).strip();
        String body = headingEnd < 0 ? "" : normalized.substring(headingEnd + 1).strip();
        return heading + "\n\n- 知识空间：" + singleLine(workspaceId)
                + (body.isBlank() ? "" : "\n\n" + body);
    }

    private int firstEntryStart(String content) {
        Matcher entries = RECORD_ENTRY.matcher(content);
        return entries.find() ? entries.start() : content.length();
    }

    private String formatTeachingCheck(String workspaceId, String attemptId, String topic, String question, String answer,
                                       int score, int maxScore, boolean passed, String feedback,
                                       String weakPoint, String reviewExplanation, String reviewSuggestion) {
        StringBuilder entry = new StringBuilder("\n## 教学检查\n\n")
                .append("- 主题：").append(singleLine(topic)).append('\n')
                .append(workspaceId == null || workspaceId.isBlank() ? "" : "- 知识空间：" + singleLine(workspaceId) + "\n")
                .append("- 检查问题：").append(singleLine(question)).append('\n')
                .append("- 我的回答：").append(singleLine(answer)).append('\n')
                .append("- 得分：").append(score).append('/').append(maxScore).append('\n')
                .append("- 结果：").append(passed ? "通过" : "需要复习").append('\n')
                .append("- 反馈：").append(singleLine(feedback)).append('\n')
                .append("- attemptId：").append(singleLine(attemptId)).append('\n');
        if (!passed && weakPoint != null) {
            entry.append("\n### 针对性复习\n\n")
                    .append("- 薄弱点：").append(singleLine(weakPoint)).append('\n')
                    .append("- 关键解释：").append(singleLine(reviewExplanation)).append('\n')
                    .append("- 复习建议：").append(singleLine(reviewSuggestion)).append('\n');
        }
        return entry.toString();
    }

    private String singleLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s*\\R\\s*", " ");
    }

    private String replaceAnswerForQuestion(String content, String workspaceId, String question, String entry) {
        String expected = normalizeQuestion(question);
        Matcher matcher = QUESTION_ANSWER_ENTRY.matcher(content);
        StringBuffer updated = new StringBuffer();
        boolean replaced = false;

        while (matcher.find()) {
            Matcher workspace = WORKSPACE_MARKER.matcher(matcher.group());
            String entryWorkspace = workspace.find() ? workspace.group(1).strip() : null;
            String recordedQuestion = WORKSPACE_MARKER.matcher(matcher.group(1)).replaceFirst("").strip();
            if (!replaced && java.util.Objects.equals(workspaceId, entryWorkspace)
                    && normalizeQuestion(recordedQuestion).equals(expected)) {
                matcher.appendReplacement(updated, Matcher.quoteReplacement(entry.stripLeading()));
                replaced = true;
            } else {
                matcher.appendReplacement(updated, Matcher.quoteReplacement(matcher.group()));
            }
        }
        if (!replaced) {
            return null;
        }

        matcher.appendTail(updated);
        return updated.toString();
    }

    private String withoutReferences(String answer) {
        return answer.strip()
                .replaceFirst("(?ms)\\n+参考来源[：:]\\s*\\n.*$", "")
                .replaceAll("(?m)^\\s*以上回答基于通用大模型知识，不是当前知识库内容。\\s*$\\R?", "")
                .strip();
    }

    private boolean isRecordableAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }

        String cleaned = withoutReferences(answer);
        if (UNRELIABLE_ANSWER_MARKERS.stream().anyMatch(cleaned::contains)) {
            return false;
        }

        String normalized = cleaned.strip();
        if (normalized.startsWith("我不知道")
                || normalized.startsWith("抱歉，我无法")
                || normalized.startsWith("抱歉，无法")) {
            return false;
        }

        // 去除 Markdown 标题和标点后仍需有实质内容，避免把“# RAG”一类失败兜底写入学习记录。
        String meaningful = normalized
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
        return meaningful.length() >= 6;
    }

    private String normalizeQuestion(String question) {
        return question.strip().replaceAll("\\s+", " ");
    }

    private String safeWorkspaceDirectory(String workspaceId) {
        return "workspace-" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(workspaceId.getBytes(StandardCharsets.UTF_8));
    }
}
