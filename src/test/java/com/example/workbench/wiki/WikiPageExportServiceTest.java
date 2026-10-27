package com.example.workbench.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class WikiPageExportServiceTest {

    private final WikiPageRepository repository = mock(WikiPageRepository.class);
    private final WikiPageExportService service = new WikiPageExportService(repository, new ObjectMapper());

    private WikiPageEntity page(String id, String title, String slug, String parentId, String content) {
        return new WikiPageEntity(id, "ws-1", parentId, title, slug, content, "owner-1",
                Instant.parse("2026-10-27T00:00:00Z"), Instant.parse("2026-10-27T01:00:00Z"));
    }

    @Test
    void exportPackagesMarkdownWithFrontMatterAndManifest() throws Exception {
        when(repository.findByWorkspaceIdOrderByTitleAsc("ws-1")).thenReturn(List.of(
                page("p1", "根笔记", "root", null, "# 根\n内容 A"),
                page("p2", "子笔记", "child", "p1", "内容 B")
        ));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export("ws-1", output);

        Map<String, String> entries = readZip(output.toByteArray());
        assertThat(entries).containsKeys("根笔记 (p1).md", "子笔记 (p2).md", "_manifest.json");
        String markdown = entries.get("根笔记 (p1).md");
        assertThat(markdown).startsWith("---\n")
                .contains("title: 根笔记")
                .contains("slug: root")
                .contains("id: p1")
                .contains("parent: \n")
                .contains("# 根\n内容 A");
        String childMarkdown = entries.get("子笔记 (p2).md");
        assertThat(childMarkdown).contains("parent: p1").contains("内容 B");

        JsonNode manifest = new ObjectMapper().readTree(entries.get("_manifest.json"));
        assertThat(manifest.get("pageCount").asInt()).isEqualTo(2);
        assertThat(manifest.get("pages").get(0).get("title").asText()).isEqualTo("根笔记");
        assertThat(manifest.get("pages").get(1).get("parentId").asText()).isEqualTo("p1");
    }

    @Test
    void duplicateTitlesAreDisambiguated() {
        String first = WikiPageExportService.fileName("同名", "a");
        java.util.Set<String> used = new java.util.HashSet<>();
        String n1 = WikiPageExportService.uniqueName(first, used);
        String n2 = WikiPageExportService.uniqueName(first, used);
        String n3 = WikiPageExportService.uniqueName(first, used);
        assertThat(n2).isEqualTo(first + " (2)");
        assertThat(n3).isEqualTo(first + " (3)");
    }

    @Test
    void fileNameStripsIllegalCharactersAndLongTitles() {
        String sanitized = WikiPageExportService.fileName("a/b\\c:d*e?f\"g<h>i|j", "id1");
        assertThat(sanitized).doesNotContain("/").doesNotContain(":").contains("(");
        String longTitle = "长".repeat(100);
        assertThat(WikiPageExportService.fileName(longTitle, "id2").length()).isLessThan(80);
        assertThat(WikiPageExportService.fileName("   ", "id3")).isEqualTo("untitled (id3)");
    }

    @Test
    void exportEmptyWorkspaceStillProducesManifest() throws Exception {
        when(repository.findByWorkspaceIdOrderByTitleAsc("ws-empty")).thenReturn(List.of());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export("ws-empty", output);

        Map<String, String> entries = readZip(output.toByteArray());
        assertThat(entries).containsOnlyKeys("_manifest.json");
        JsonNode manifest = new ObjectMapper().readTree(entries.get("_manifest.json"));
        assertThat(manifest.get("pageCount").asInt()).isZero();
    }

    private Map<String, String> readZip(byte[] data) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
