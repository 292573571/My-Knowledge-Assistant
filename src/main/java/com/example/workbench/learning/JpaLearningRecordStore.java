package com.example.workbench.learning;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.RagSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaLearningRecordStore implements LearningRecordStore {

    private final LearningRecordRepository repository;
    private final LearningRecordOutboxRepository outboxRepository;
    private final FormalNoteRepository formalNoteRepository;
    private final FormalNoteOutboxRepository formalNoteOutboxRepository;
    private final ObjectMapper objectMapper;

    JpaLearningRecordStore(LearningRecordRepository repository, LearningRecordOutboxRepository outboxRepository,
                           FormalNoteRepository formalNoteRepository, FormalNoteOutboxRepository formalNoteOutboxRepository,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.formalNoteRepository = formalNoteRepository;
        this.formalNoteOutboxRepository = formalNoteOutboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public String recordChat(AppUser user, String workspaceId, LocalDate date, String question, String answer,
                             List<RagSource> sources, String markdown) {
        String sourceKey = "chat:" + user.getId() + ":" + workspaceId + ":" + date + ":" + hash(question.trim().replaceAll("\\s+", " "));
        LearningRecordEntity entity = repository.findFirstBySourceKey(sourceKey).orElse(null);
        LearningRecordEntry entry = new LearningRecordEntry(
                entity == null ? UUID.randomUUID().toString() : entity.id(), user.getId(), workspaceId, date,
                LearningRecordType.CHAT, question, answer, null, null, null, null, null, null, null,
                null, null, null, json(sources), markdown, sourceKey, false,
                entity == null ? Instant.now() : entity.toEntry().createdAt(), Instant.now());
        saveAndEnqueue(entity, entry);
        return date.toString();
    }

    @Override
    @Transactional
    public String recordTeachingExplanation(AppUser user, String workspaceId, LocalDate date, String sessionId,
                                            String topic, String explanation, List<RagSource> sources, String markdown) {
        String sourceKey = "teaching-explanation:" + user.getId() + ":" + workspaceId + ":" + sessionId;
        LearningRecordEntity entity = repository.findFirstBySourceKey(sourceKey).orElse(null);
        LearningRecordEntry entry = LearningRecordEntry.teachingExplanation(
                entity == null ? UUID.randomUUID().toString() : entity.id(), user.getId(), workspaceId, date,
                sessionId, topic, explanation, json(sources), markdown, sourceKey,
                entity == null ? Instant.now() : entity.toEntry().createdAt(), Instant.now());
        saveAndEnqueue(entity, entry);
        return date.toString();
    }

    @Override
    @Transactional
    public String recordTeachingCheck(AppUser user, String workspaceId, LocalDate date, String attemptId,
                                      String topic, String question, String answer, int score, int maxScore,
                                      boolean passed, String feedback, String weakPoint, String reviewExplanation,
                                      String reviewSuggestion, String markdown) {
        String sourceKey = "teaching-check:" + attemptId;
        LearningRecordEntity entity = repository.findFirstBySourceKey(sourceKey).orElse(null);
        LearningRecordEntry entry = new LearningRecordEntry(
                entity == null ? UUID.randomUUID().toString() : entity.id(), user.getId(), workspaceId, date,
                LearningRecordType.TEACHING_CHECK, question, answer, topic, attemptId, null, score, maxScore,
                passed, feedback, weakPoint, reviewExplanation, reviewSuggestion, "[]", markdown, sourceKey, false,
                entity == null ? Instant.now() : entity.toEntry().createdAt(), Instant.now());
        saveAndEnqueue(entity, entry);
        return date.toString();
    }

    @Override
    @Transactional
    public String recordTeachingPractice(AppUser user, String workspaceId, LocalDate date, String checkId,
                                         String practiceId, String topic, String question, String answer,
                                         int score, int maxScore, boolean passed, String feedback, String markdown) {
        String sourceKey = "teaching-practice:" + practiceId;
        LearningRecordEntity entity = repository.findFirstBySourceKey(sourceKey).orElse(null);
        LearningRecordEntry entry = new LearningRecordEntry(
                entity == null ? UUID.randomUUID().toString() : entity.id(), user.getId(), workspaceId, date,
                LearningRecordType.TEACHING_PRACTICE, question, answer, topic, null, practiceId, score, maxScore,
                passed, feedback, null, null, null, "[]", markdown, sourceKey, false,
                entity == null ? Instant.now() : entity.toEntry().createdAt(), Instant.now());
        saveAndEnqueue(entity, entry);
        return date.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LearningRecordEntry> visible(AppUser user, String workspaceId, boolean includeLegacy) {
        return repository.findVisible(user.getId(), workspaceId, includeLegacy).stream().map(LearningRecordEntity::toEntry).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LearningRecordEntry> visibleOnDate(AppUser user, String workspaceId, LocalDate date, boolean includeLegacy) {
        return repository.findVisibleOnDate(user.getId(), workspaceId, date, includeLegacy).stream().map(LearningRecordEntity::toEntry).toList();
    }

    @Override
    @Transactional
    public void replaceOnDate(AppUser user, String workspaceId, LocalDate date, List<LearningRecordEntry> entries,
                              boolean includeLegacy) {
        List<LearningRecordEntity> existing = repository.findDateForUpdate(user.getId(), workspaceId, date, includeLegacy);
        existing.forEach(entity -> repository.delete(entity));
        entries.forEach(entry -> saveAndEnqueue(null, entry));
    }

    @Override
    @Transactional
    public void deleteOnDate(AppUser user, String workspaceId, LocalDate date, boolean includeLegacy) {
        List<LearningRecordEntity> existing = repository.findDateForUpdate(user.getId(), workspaceId, date, includeLegacy);
        existing.forEach(entity -> {
            repository.delete(entity);
            outboxRepository.save(new LearningRecordOutboxEntity(entity.id(), entity.ownerUserId(), entity.workspaceId(), entity.recordDate()));
        });
    }

    @Override
    @Transactional
    public void saveFormalNote(AppUser user, String workspaceId, LocalDate date, String fileName, String path,
                               String content) {
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算正式笔记哈希", exception);
        }
        FormalNoteEntity note = formalNoteRepository.findByOwnerUserIdAndWorkspaceIdAndNoteDate(
                user.getId(), workspaceId, date).orElse(null);
        if (note == null) {
            note = formalNoteRepository.save(new FormalNoteEntity(user.getId(), workspaceId, date, fileName, path, content, hash));
        } else {
            note.update(fileName, path, content, hash);
        }
        formalNoteOutboxRepository.save(new FormalNoteOutboxEntity(note.id()));
    }

    private void saveAndEnqueue(LearningRecordEntity existing, LearningRecordEntry entry) {
        LearningRecordEntity entity = existing == null ? new LearningRecordEntity(entry) : existing;
        entity.update(entry);
        repository.save(entity);
        outboxRepository.save(new LearningRecordOutboxEntity(entry.id(), entry.ownerUserId(), entry.workspaceId(), entry.recordDate()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存学习记录来源", exception);
        }
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算学习记录幂等键", exception);
        }
    }
}
