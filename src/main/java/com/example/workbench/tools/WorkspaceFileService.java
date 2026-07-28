package com.example.workbench.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceFileService {

    private static final Path WORKSPACE = Path.of("").toAbsolutePath().normalize();
    private static final Path DOCS = WORKSPACE.resolve("docs").normalize();
    private static final Path NOTES = WORKSPACE.resolve("notes").normalize();

    public String readNoteOrDoc(String fileName) throws IOException {
        Path path = resolveReadableFile(fileName);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public Path writeNote(String fileName, String content) throws IOException {
        Path path = resolveWritableNote(fileName);
        Files.createDirectories(NOTES);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return WORKSPACE.relativize(path);
    }

    public List<String> listReadableFiles() throws IOException {
        return List.of(DOCS, NOTES).stream()
                .filter(Files::exists)
                .flatMap(root -> listFiles(root).stream())
                .map(path -> WORKSPACE.relativize(path).toString())
                .sorted()
                .toList();
    }

    private Path resolveReadableFile(String fileName) {
        Path normalizedFile = Path.of(fileName).normalize();

        if (normalizedFile.isAbsolute() || normalizedFile.startsWith("..")) {
            throw new IllegalArgumentException("只允许读取 workspace 内的 docs 或 notes 文件");
        }

        Path docsPath = DOCS.resolve(normalizedFile.getFileName()).normalize();
        if (Files.exists(docsPath) && docsPath.startsWith(DOCS)) {
            return docsPath;
        }

        Path notesPath = NOTES.resolve(normalizedFile.getFileName()).normalize();
        if (Files.exists(notesPath) && notesPath.startsWith(NOTES)) {
            return notesPath;
        }

        throw new IllegalArgumentException("未找到可读取文件：" + fileName);
    }

    private Path resolveWritableNote(String fileName) {
        Path normalizedFile = Path.of(fileName).normalize();

        if (normalizedFile.isAbsolute() || normalizedFile.startsWith("..")) {
            throw new IllegalArgumentException("只允许写入 notes 目录");
        }

        Path path = NOTES.resolve(normalizedFile.getFileName()).normalize();

        if (!path.startsWith(NOTES)) {
            throw new IllegalArgumentException("只允许写入 notes 目录");
        }

        return path;
    }

    private List<Path> listFiles(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md") || path.toString().endsWith(".txt"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取文件列表失败：" + root, exception);
        }
    }
}
