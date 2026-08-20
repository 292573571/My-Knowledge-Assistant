package com.example.workbench.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public class FileTools {

    private final List<Path> allowedRoots;
    private final long maxBytes;

    public FileTools(
            @Value("${app.files.allowed-directories:data,docs}") String directories,
            @Value("${app.files.max-read-bytes:10485760}") long maxBytes
    ) {
        this.allowedRoots = List.of(directories.split(",")).stream()
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .toList();
        this.maxBytes = Math.max(1, maxBytes);
    }

    public String readTextFile(Path path) throws IOException {
        Path safePath = safePath(path);
        if (Files.size(safePath) > maxBytes) throw new IOException("文件超过读取大小限制");
        return Files.readString(safePath);
    }

    public boolean exists(Path path) {
        try {
            return Files.exists(safePath(path), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            return false;
        }
    }

    private Path safePath(Path path) throws IOException {
        if (path == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件路径不能为空");
        Path normalized = path.toAbsolutePath().normalize();
        boolean lexicallyAllowed = allowedRoots.stream().anyMatch(normalized::startsWith);
        if (!lexicallyAllowed) throw new IOException("文件路径不在允许目录内");
        Path realPath = normalized.toRealPath();
        boolean allowed = allowedRoots.stream().anyMatch(root -> {
            try {
                return realPath.startsWith(root.toRealPath());
            } catch (IOException exception) {
                return false;
            }
        });
        if (!allowed) throw new IOException("文件路径不在允许目录内");
        return realPath;
    }
}
