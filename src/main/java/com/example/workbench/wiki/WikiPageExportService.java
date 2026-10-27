package com.example.workbench.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * 知识空间 Wiki 导出：把全部页面打包成 Markdown zip（每页一个 .md + _manifest.json 层级清单）。
 *
 * <p>纯数据导出，用于本地备份 / 迁移；文件名经安全清洗，重名自动加序号。
 */
@Service
public class WikiPageExportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final WikiPageRepository repository;
    private final ObjectMapper objectMapper;

    public WikiPageExportService(WikiPageRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void export(String workspaceId, OutputStream output) throws IOException {
        List<WikiPageEntity> pages = repository.findByWorkspaceIdOrderByTitleAsc(workspaceId);
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            Set<String> usedNames = new HashSet<>();
            for (WikiPageEntity page : pages) {
                zip.putNextEntry(new ZipEntry(uniqueName(fileName(page.getTitle(), page.getId()), usedNames) + ".md"));
                zip.write(pageMarkdown(page).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("_manifest.json"));
            zip.write(objectMapper.writeValueAsBytes(manifest(pages)));
            zip.closeEntry();
        }
    }

    private String pageMarkdown(WikiPageEntity page) {
        StringBuilder builder = new StringBuilder();
        builder.append("---\n")
                .append("title: ").append(page.getTitle() == null ? "" : page.getTitle()).append('\n')
                .append("slug: ").append(page.getSlug() == null ? "" : page.getSlug()).append('\n')
                .append("parent: ").append(page.getParentId() == null ? "" : page.getParentId()).append('\n')
                .append("id: ").append(page.getId() == null ? "" : page.getId()).append('\n')
                .append("updated: ").append(page.getUpdatedAt() == null ? "" : ISO.format(page.getUpdatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))).append('\n')
                .append("---\n\n");
        if (page.getContentMd() != null) {
            builder.append(page.getContentMd());
        }
        return builder.toString();
    }

    private Object manifest(List<WikiPageEntity> pages) {
        List<?> items = pages.stream().map(page -> new java.util.LinkedHashMap<String, Object>() {{
            put("id", page.getId());
            put("title", page.getTitle());
            put("slug", page.getSlug());
            put("parentId", page.getParentId());
            put("updatedAt", page.getUpdatedAt() == null ? null : ISO.format(page.getUpdatedAt()));
        }}).toList();
        return new java.util.LinkedHashMap<String, Object>() {{
            put("workspaceId", pages.isEmpty() ? null : pages.get(0).getWorkspaceId());
            put("pageCount", pages.size());
            put("pages", items);
        }};
    }

    /** 清洗文件名：去除路径分隔符与非法字符，限制长度。 */
    static String fileName(String title, String id) {
        String base = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
        if (base.isEmpty()) {
            base = "untitled";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60).trim();
        }
        return base + " (" + id + ")";
    }

    /** 重名去重：第二次出现起追加 " (2)"、" (3)"… */
    static String uniqueName(String candidate, Set<String> used) {
        if (used.add(candidate)) {
            return candidate;
        }
        for (int index = 2; ; index++) {
            String next = candidate + " (" + index + ")";
            if (used.add(next)) {
                return next;
            }
        }
    }
}
