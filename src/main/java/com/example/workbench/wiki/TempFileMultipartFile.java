package com.example.workbench.wiki;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.lang.NonNull;
import org.springframework.web.multipart.MultipartFile;

/**
 * 把异步导入任务落盘的临时文件包装回 {@link MultipartFile}，让
 * {@link WikiPageService#importFromFile} 及其下游解析链路无需感知改动。
 *
 * <p>请求结束后 Servlet 容器会清理上传临时文件，因此任务提交时先把上传内容
 * 拷贝到独立临时文件，任务完成后再删除。
 */
class TempFileMultipartFile implements MultipartFile {

    private final Path file;
    private final String originalFilename;
    private final String contentType;

    TempFileMultipartFile(Path file, String originalFilename, String contentType) {
        this.file = file;
        this.originalFilename = originalFilename == null || originalFilename.isBlank()
                ? file.getFileName().toString()
                : originalFilename;
        this.contentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        try {
            return Files.size(file) == 0;
        } catch (IOException exception) {
            return true;
        }
    }

    @Override
    public long getSize() {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            return 0;
        }
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(file);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(file);
    }

    @Override
    public void transferTo(@NonNull File destination) throws IOException {
        Files.copy(file, destination.toPath());
    }
}
