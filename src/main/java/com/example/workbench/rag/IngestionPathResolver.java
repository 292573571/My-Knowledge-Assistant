package com.example.workbench.rag;

import com.example.workbench.workspace.WorkspaceAccessContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档摄入过程中的路径解析与安全检查纯函数集合。
 *
 * 不持有任何共享可变状态，仅依赖注入的 docsDirectory 与编译期常量，
 * 因此可安全在摄入锁临界区外调用，也便于独立测试。
 */
public final class IngestionPathResolver {

    private static final Path WORKSPACE_DIRECTORY = Path.of("").toAbsolutePath().normalize();
    private static final long MAX_WORKSPACE_DOCUMENT_BYTES = 50L * 1024 * 1024;

    private final Path docsDirectory;

    public IngestionPathResolver(Path docsDirectory) {
        this.docsDirectory = docsDirectory.toAbsolutePath().normalize();
    }

    public void requireWorkspaceWrite(WorkspaceAccessContext access) {
        if (!access.canWrite()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色不能维护空间文档");
        }
    }

    public Path workspaceDirectory(WorkspaceAccessContext access) {
        Path directory = docsDirectory.resolve("workspaces").resolve(access.workspaceId()).normalize();
        if (!directory.startsWith(docsDirectory)) {
            throw new IllegalArgumentException("Invalid workspace document directory");
        }
        return directory;
    }

    public Path resolveAllowedPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }

        Path resolvedPath = Path.of(path).normalize();
        if (!resolvedPath.isAbsolute()) {
            resolvedPath = WORKSPACE_DIRECTORY.resolve(resolvedPath).normalize();
        }

        if (!resolvedPath.startsWith(docsDirectory)) {
            throw new IllegalArgumentException("Path must be under docs directory");
        }

        return resolvedPath;
    }

    public Path resolveIndexedPath(String indexedPath) {
        String normalizedPath = indexedPath.replace('\\', '/');
        Path resolvedPath = normalizedPath.startsWith("docs/")
                ? docsDirectory.resolve(normalizedPath.substring("docs/".length())).normalize()
                : Path.of(indexedPath).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(docsDirectory)) {
            throw new IllegalArgumentException("Indexed document path must be under docs directory");
        }
        return resolvedPath;
    }

    public String workspaceRelativePath(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.startsWith(docsDirectory)) {
            return "docs/" + docsDirectory.relativize(normalizedPath).toString();
        }

        if (normalizedPath.startsWith(WORKSPACE_DIRECTORY)) {
            return WORKSPACE_DIRECTORY.relativize(normalizedPath).toString();
        }

        return path.toString();
    }

    public boolean isSupportedDocument(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".md") || fileName.endsWith(".txt")
                || fileName.endsWith(".pdf") || fileName.endsWith(".docx") || fileName.endsWith(".doc")
                || fileName.endsWith(".xlsx") || fileName.endsWith(".xls")
                || fileName.endsWith(".pptx") || fileName.endsWith(".ppt")
                || fileName.endsWith(".csv") || fileName.endsWith(".json") || fileName.endsWith(".jsonl")
                || fileName.endsWith(".xml") || fileName.endsWith(".rtf") || fileName.endsWith(".odt")
                || fileName.endsWith(".html") || fileName.endsWith(".htm")
                || fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg");
    }

    public boolean isBinaryDocument(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc")
                || lower.endsWith(".xlsx") || lower.endsWith(".xls")
                || lower.endsWith(".pptx") || lower.endsWith(".ppt")
                || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    public boolean isImageDocument(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    public boolean isIndexableDocument(Path path) {
        return isSupportedDocument(path)
                && !"LEARNING_RECORD".equals(documentCategory(workspaceRelativePath(path).replace('\\', '/')));
    }

    public String documentCategory(String path) {
        if (path.startsWith("docs/learning-records/")) {
            return "LEARNING_RECORD";
        }
        if (path.startsWith("docs/manual-notes/")) {
            return "FORMAL_NOTE";
        }
        return "SOURCE";
    }

    public String ownerUserId(String path) {
        if (!path.startsWith("docs/learning-records/") && !path.startsWith("docs/manual-notes/")) {
            return "";
        }
        var matcher = java.util.regex.Pattern.compile("/user-([^/]+)/").matcher(path);
        return matcher.find() ? matcher.group(1) : "";
    }

    public String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    public String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public String safeOriginalFileName(String originalFileName) {
        String fileName = originalFileName == null ? "" : Path.of(originalFileName).getFileName().toString().strip();
        String lower = fileName.toLowerCase();
        if (fileName.isBlank() || (!lower.endsWith(".md") && !lower.endsWith(".txt")
                && !lower.endsWith(".pdf") && !lower.endsWith(".docx") && !lower.endsWith(".doc")
                && !lower.endsWith(".xlsx") && !lower.endsWith(".xls")
                && !lower.endsWith(".pptx") && !lower.endsWith(".ppt")
                && !lower.endsWith(".csv") && !lower.endsWith(".json") && !lower.endsWith(".jsonl")
                && !lower.endsWith(".xml") && !lower.endsWith(".rtf") && !lower.endsWith(".odt")
                && !lower.endsWith(".html") && !lower.endsWith(".htm")
                && !lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文档仅支持 Markdown、TXT、HTML、PDF、DOCX、DOC、XLSX、XLS、PPTX、PPT、CSV、JSON、XML、RTF、ODT、PNG 或 JPEG");
        }
        if (fileName.length() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档文件名过长");
        }
        return fileName;
    }

    public void validateUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的文档");
        }
        if (file.getSize() > MAX_WORKSPACE_DOCUMENT_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档不能超过 50 MB");
        }
    }

    public byte[] uploadedContent(MultipartFile file) {
        validateUploadedFile(file);
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read uploaded document", exception);
        }
    }
}
