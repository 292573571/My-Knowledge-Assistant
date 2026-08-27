package com.example.workbench.learning;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class LearningRecordProjectionService {

    private final LearningRecordOutboxRepository outboxRepository;
    private final LearningRecordRepository recordRepository;
    private final Path recordsDirectory = Path.of("docs", "learning-records");
    private final String workerId = UUID.randomUUID().toString();
    private final TransactionTemplate transactions;

    LearningRecordProjectionService(LearningRecordOutboxRepository outboxRepository,
                                    LearningRecordRepository recordRepository,
                                    org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.recordRepository = recordRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void projectOne() {
        Claim claim = transactions.execute(status -> {
            LearningRecordOutboxEntity event = outboxRepository.findNextForUpdate(Instant.now()).orElse(null);
            if (event == null) return null;
            event.claim(workerId, Instant.now().plusSeconds(120));
            outboxRepository.save(event);
            return new Claim(event.id(), event.ownerUserId(), event.workspaceId(), event.recordDate(), event.attemptCount(), event.generation());
        });
        if (claim == null) return;
        try {
            List<LearningRecordEntity> records = claim.workspaceId() == null
                    ? recordRepository.findByOwnerUserIdAndRecordDateAndWorkspaceIdIsNullOrderByCreatedAtAsc(
                            claim.ownerUserId(), claim.recordDate())
                    : recordRepository.findByOwnerUserIdAndRecordDateAndWorkspaceIdOrderByCreatedAtAsc(
                            claim.ownerUserId(), claim.recordDate(), claim.workspaceId());
            StringBuilder content = new StringBuilder("# ").append(claim.recordDate()).append(" 学习记录\n");
            records.forEach(record -> content.append("\n").append(record.toEntry().markdown().strip()).append("\n"));
            Path target = recordsDirectory.resolve("user-" + claim.ownerUserId())
                    .resolve(claim.workspaceId() == null ? "legacy" : safeWorkspace(claim.workspaceId()))
                    .resolve(claim.recordDate() + ".md").normalize();
            Files.createDirectories(target.getParent());
            if (records.isEmpty()) {
                Files.deleteIfExists(target);
                finish(claim);
                return;
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + workerId);
            Files.writeString(temporary, content.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            finish(claim);
        } catch (Exception exception) {
            fail(claim, exception);
            throw new IllegalStateException("学习记录投影失败", exception);
        }
    }

    private void finish(Claim claim) {
        transactions.executeWithoutResult(status -> {
            if (outboxRepository.renewLease(claim.id(), LearningRecordOutboxEntity.Status.PROCESSING,
                    workerId, claim.generation(), Instant.now().plusSeconds(120)) == 0) return;
            outboxRepository.done(claim.id(),
                LearningRecordOutboxEntity.Status.PROCESSING, LearningRecordOutboxEntity.Status.DONE,
                workerId, claim.generation(), Instant.now());
        });
    }

    private void fail(Claim claim, Exception exception) {
        transactions.executeWithoutResult(status -> outboxRepository.retry(claim.id(),
                LearningRecordOutboxEntity.Status.PROCESSING, LearningRecordOutboxEntity.Status.QUEUED,
                workerId, claim.generation(), Instant.now().plusSeconds(Math.min(3600, 5L * claim.attemptCount())),
                exception.getClass().getSimpleName()));
    }

    private String safeWorkspace(String workspaceId) {
        return workspaceId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private record Claim(String id, Long ownerUserId, String workspaceId, java.time.LocalDate recordDate,
                         int attemptCount, long generation) {}
}
