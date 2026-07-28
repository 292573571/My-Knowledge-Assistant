package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvalImportStorage {
    private final EvalImportRepository repository;
    private final Path rootDirectory;

    public EvalImportStorage(EvalImportRepository repository,
                             @Value("${app.eval.import-directory:data/eval-imports}") String importDirectory) {
        this.repository = repository;
        this.rootDirectory = Path.of(importDirectory).toAbsolutePath().normalize();
    }

    @Transactional
    public void save(AppUser user, String originalFileName, String contentType, byte[] content, int importedCount) {
        String extension = extension(originalFileName);
        String storedFileName = UUID.randomUUID() + extension;
        Path userDirectory = rootDirectory.resolve("user-" + user.getId()).normalize();
        Path target = userDirectory.resolve(storedFileName).normalize();
        if (!target.startsWith(userDirectory)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件名无效");
        try {
            Files.createDirectories(userDirectory);
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
            repository.save(new EvalImportEntity(user, originalFileName, storedFileName,
                    contentType == null ? "application/octet-stream" : contentType, content.length, importedCount));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save eval import", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<EvalImportResponse> list(AppUser user) {
        return repository.findAllByOwnerIdOrderByCreatedAtDesc(user.getId()).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public EvalImportedFile download(AppUser user, Long id) {
        EvalImportEntity item = repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "导入记录不存在"));
        Path file = rootDirectory.resolve("user-" + user.getId()).resolve(item.getStoredFileName()).normalize();
        if (!file.startsWith(rootDirectory) || !Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导入文件不存在");
        try {
            return new EvalImportedFile(Files.readAllBytes(file), item.getOriginalFileName(), item.getContentType());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read eval import", exception);
        }
    }

    private EvalImportResponse response(EvalImportEntity item) {
        return new EvalImportResponse(item.getId(), item.getOriginalFileName(), item.getContentType(), item.getFileSize(), item.getImportedCount(), item.getCreatedAt());
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase();
    }
}
