package com.example.workbench.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class DocumentCategoryWikiTest {

    @Test
    void wikiPathResolvesToWikiPageCategory() {
        assertEquals(DocumentCategory.WIKI_PAGE, DocumentCategory.of("docs/wiki/personal-1/page-1.md"));
        assertEquals(DocumentCategory.WIKI_PAGE, DocumentCategory.of("docs/wiki/team-9/abc.md"));
    }

    @Test
    void wikiPageIsNotExcludedFromKnowledgeBaseRetrieval() {
        // RagService.filterByThreshold 仅按 isLearningRecord 排除；wiki 页必须可通过该过滤被召回。
        assertFalse(DocumentCategory.isLearningRecord(DocumentCategory.WIKI_PAGE, "docs/wiki/personal-1/page-1.md"));
        assertFalse(DocumentCategory.isExcludedFromKnowledgeBase(DocumentCategory.WIKI_PAGE, "docs/wiki/personal-1/page-1.md"));
    }

    @Test
    void ingestionPathResolverMapsWikiPrefix() {
        IngestionPathResolver resolver = new IngestionPathResolver(java.nio.file.Path.of("docs"));
        assertEquals("WIKI_PAGE", resolver.documentCategory("docs/wiki/team-9/abc.md"));
        // 重建索引时 wiki 文件应可被摄入（非学习记录，因此不被 isIndexableDocument 排除）。
        assertFalse(DocumentCategory.isLearningRecord("WIKI_PAGE", "docs/wiki/team-9/abc.md"));
    }
}
