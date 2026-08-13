package com.example.workbench.learning;

import com.example.workbench.auth.AppUserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class LearningRecordLegacyImporter {

    private static final Logger log = LoggerFactory.getLogger(LearningRecordLegacyImporter.class);
    private static final Path RECORDS_DIRECTORY = Path.of("docs", "learning-records");

    private final AppUserRepository userRepository;
    private final LearningRecordRepository recordRepository;
    private final LearningRecordOutboxRepository outboxRepository;

    LearningRecordLegacyImporter(AppUserRepository userRepository, LearningRecordRepository recordRepository,
                                 LearningRecordOutboxRepository outboxRepository) {
        this.userRepository = userRepository;
        this.recordRepository = recordRepository;
        this.outboxRepository = outboxRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void importLegacyFiles() {
        if (!Files.isDirectory(RECORDS_DIRECTORY)) return;
        try (var users = Files.list(RECORDS_DIRECTORY)) {
            users.filter(Files::isDirectory).forEach(this::importUserDirectory);
        } catch (IOException exception) {
            log.warn("历史学习记录导入失败 errorType={}", exception.getClass().getSimpleName());
        }
    }

    private void importUserDirectory(Path directory) {
        String folder = directory.getFileName().toString();
        if (!folder.startsWith("user-")) return;
        Long userId;
        try {
            userId = Long.valueOf(folder.substring("user-".length()));
        } catch (NumberFormatException ignored) {
            return;
        }
        if (userRepository.findById(userId).isEmpty()) return;
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .forEach(path -> importFile(path, userId));
        } catch (IOException exception) {
            log.warn("历史学习记录目录读取失败 userId={} errorType={}", userId, exception.getClass().getSimpleName());
        }
    }

    private void importFile(Path path, Long userId) {
        String sourceKey = "legacy-file:" + path.toString().replace('\\', '/');
        if (recordRepository.findFirstBySourceKey(sourceKey).isPresent()) return;
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (content.isBlank()) return;
            String dateText = path.getFileName().toString().replaceFirst("\\.md$", "");
            LocalDate date = LocalDate.parse(dateText);
            String body = content.replaceFirst("^#\\s*" + dateText + "\\s*学习记录\\s*", "").strip();
            LearningRecordEntry entry = new LearningRecordEntry(
                    UUID.randomUUID().toString(), userId, null, date, LearningRecordType.LEGACY,
                    null, null, null, null, null, null, null, null, null, null, null, null, "[]",
                    body, sourceKey, true, Instant.now(), Instant.now());
            recordRepository.save(new LearningRecordEntity(entry));
            outboxRepository.save(new LearningRecordOutboxEntity(entry.id(), userId, null, date));
            log.info("历史学习记录已导入 userId={} date={}", userId, date);
        } catch (IOException | RuntimeException exception) {
            log.warn("历史学习记录导入失败 userId={} path={} errorType={}", userId, path, exception.getClass().getSimpleName());
        }
    }
}
