package com.example.workbench.learning;

import com.example.workbench.rag.DocumentIngestionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class FormalNoteProjectionService {
    private final FormalNoteOutboxRepository outboxRepository;
    private final FormalNoteRepository noteRepository;
    private final DocumentIngestionService ingestionService;
    private final String workerId = UUID.randomUUID().toString();
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    FormalNoteProjectionService(FormalNoteOutboxRepository outboxRepository, FormalNoteRepository noteRepository,
                                DocumentIngestionService ingestionService,
                                org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.noteRepository = noteRepository;
        this.ingestionService = ingestionService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void projectOne() {
        Claim claim = transactions.execute(status -> {
            FormalNoteOutboxEntity event = outboxRepository.findNextForUpdate(Instant.now()).orElse(null);
            if (event == null) return null;
            event.claim(workerId, Instant.now().plusSeconds(300));
            outboxRepository.save(event);
            FormalNoteEntity note = noteRepository.findById(event.noteId()).orElse(null);
            return new Claim(event.id(), event.noteId(), note == null ? null : new NoteData(note.path(),
                    note.content(), String.valueOf(note.ownerUserId()), note.workspaceId()), event.attemptCount());
        });
        if (claim == null) return;
        try {
            if (claim.note() == null) {
                finish(claim.eventId());
                return;
            }
            Path path = Path.of(claim.note().path()).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            Files.writeString(path, claim.note().content(), StandardCharsets.UTF_8);
            ingestionService.ingestFormalNote(path.toString(), claim.note().ownerUserId(), claim.note().workspaceId());
            transactions.executeWithoutResult(status -> noteRepository.findById(claim.noteId()).ifPresent(note -> {
                note.markIndexed();
                noteRepository.save(note);
            }));
            finish(claim.eventId());
        } catch (Exception exception) {
            transactions.executeWithoutResult(status -> noteRepository.findById(claim.noteId()).ifPresent(note -> {
                note.markIndexFailed();
                noteRepository.save(note);
            }));
            transactions.executeWithoutResult(status -> outboxRepository.findById(claim.eventId()).ifPresent(event -> {
                event.retry(Instant.now().plusSeconds(Math.min(3600, 5L * claim.attemptCount())),
                        exception.getClass().getSimpleName());
                outboxRepository.save(event);
            }));
            throw new IllegalStateException("正式笔记投影失败", exception);
        }
    }

    private void finish(String eventId) {
        transactions.executeWithoutResult(status -> outboxRepository.findById(eventId).ifPresent(event -> {
            event.done(Instant.now());
            outboxRepository.save(event);
        }));
    }

    private record Claim(String eventId, String noteId, NoteData note, int attemptCount) {}
    private record NoteData(String path, String content, String ownerUserId, String workspaceId) {}
}
