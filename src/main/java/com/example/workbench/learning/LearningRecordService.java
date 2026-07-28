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
    private static final List<String> UNRELIABLE_ANSWER_MARKERS = List.of(
            "我在当前知识库中没有找到足够信息和依据来回答这个问题",
            "当前知识库没有足够信息回答该问题",
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
            String entry = formatEntry(question, answer);
            String existingContent = Files.readString(record, StandardCharsets.UTF_8);
            String updatedContent = replaceAnswerForQuestion(existingContent, question, entry);
            if (updatedContent != null) {
                // 同一问题保留最新的有效回答，修正模型切换后产生的旧答案，同时避免重复条目。
                Files.writeString(record, updatedContent, StandardCharsets.UTF_8);
            } else {
                Files.writeString(record, entry, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException exception) {
            // Learning notes must not make an otherwise successful chat request fail.
            log.warn("Learning record could not be saved userId={} path={}", user.getId(), record, exception);
        }
    }

    public List<LearningRecordSummary> list(AppUser user) {
        Path directory = recordsDirectory.resolve(userDirectory(user));
        if (!Files.exists(directory)) {
            return List.of();
        }

        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .map(this::summary)
                    .sorted(Comparator.comparing(LearningRecordSummary::date).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list learning records", exception);
        }
    }

    public LearningRecordDetail detail(AppUser user, String date) {
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习记录不存在");
        }

        Path record = recordsDirectory.resolve(userDirectory(user)).resolve(date + ".md").normalize();
        if (!record.startsWith(recordsDirectory) || !Files.isRegularFile(record)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习记录不存在");
        }

        try {
            return new LearningRecordDetail(date, date + " 学习记录", Files.readString(record, StandardCharsets.UTF_8), Files.getLastModifiedTime(record).toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read learning record", exception);
        }
    }

    public synchronized LearningRecordDetail update(AppUser user, String date, String content) {
        Path record = requireRecord(user, date);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学习记录内容不能为空");
        }
        if (content.length() > 200_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学习记录内容过长");
        }

        try {
            Files.writeString(record, content.strip() + "\n", StandardCharsets.UTF_8);
            return detail(user, date);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to update learning record", exception);
        }
    }

    public synchronized void delete(AppUser user, String date) {
        Path record = requireRecord(user, date);
        documentIngestionService.deleteIndexedPath(workspacePath(record));
        try {
            Files.delete(record);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete learning record", exception);
        }
    }

    public synchronized FormalNoteResult promote(AppUser user, String date) {
        return promote(user, date, null);
    }

    public synchronized FormalNoteResult promote(AppUser user, String date, String editedContent) {
        Path record = requireRecord(user, date);
        Path note = notesDirectory.resolve(userDirectory(user)).resolve(date + "-learning-note.md").normalize();
        if (!note.startsWith(notesDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正式笔记路径无效");
        }

        try {
            Files.createDirectories(note.getParent());
            String recordContent = editedContent == null ? Files.readString(record, StandardCharsets.UTF_8) : validateContent(editedContent);
            String body = withoutGeneratedTitle(date, recordContent);
            String noteContent = "# " + date + " 正式笔记\n\n" + body.strip() + "\n";
            String learningRecordContent = "# " + date + " 学习记录\n\n" + body.strip() + "\n";
            Files.writeString(note, noteContent, StandardCharsets.UTF_8);
            // 同一学习日期反复提升只更新同一份正式笔记，先清理历史索引避免旧版本重复显示。
            documentIngestionService.deleteIndexedPath(workspacePath(note));
            documentIngestionService.ingestDocument(note.toString(), true);
            // 两类文档共享正文，但保留各自的标题与语义，学习记录不参与知识库索引。
            Files.writeString(record, learningRecordContent, StandardCharsets.UTF_8);
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

    private String formatEntry(String question, String answer) {
        return "\n## 问题\n\n"
                + question.strip()
                + "\n\n## 回答\n\n"
                + withoutReferences(answer)
                + "\n";
    }

    private String replaceAnswerForQuestion(String content, String question, String entry) {
        String expected = normalizeQuestion(question);
        Matcher matcher = QUESTION_ANSWER_ENTRY.matcher(content);
        StringBuffer updated = new StringBuffer();
        boolean replaced = false;

        while (matcher.find()) {
            if (!replaced && normalizeQuestion(matcher.group(1)).equals(expected)) {
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
}
