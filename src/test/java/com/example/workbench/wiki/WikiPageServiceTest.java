package com.example.workbench.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.kg.KnowledgeGraphService;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.ParsedDocument;
import com.example.workbench.wiki.WikiImportContent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class WikiPageServiceTest {

    @Mock
    private WikiPageRepository repository;

    @Mock
    private DocumentIngestionService ingestionService;

    @Mock
    private KnowledgeGraphService graphService;

    @InjectMocks
    private WikiPageService service;

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = org.mockito.Mockito.mock(AppUser.class);
        Mockito.lenient().when(user.getId()).thenReturn(1L);
    }

    @Test
    void createAutoGeneratesUniqueSlugAndIngests() {
        when(repository.save(any(WikiPageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.countByWorkspaceIdAndSlug(eq("ws-1"), eq("hello-world"))).thenReturn(0L);

        WikiPageDto created = service.create(user, "ws-1", new WikiPageRequest("Hello World", "", null, "# 内容"));

        assertEquals("hello-world", created.slug());
        ArgumentCaptor<WikiPageEntity> captor = ArgumentCaptor.forClass(WikiPageEntity.class);
        verify(repository).save(captor.capture());
        assertEquals("ws-1", captor.getValue().getWorkspaceId());
        verify(ingestionService).ingestWikiPage(
                eq(created.id()), eq("Hello World"), eq("1"), eq("ws-1"), eq("# 内容"));
        verify(graphService).rebuildForPage(eq("ws-1"), any(), eq("Hello World"), any(), eq("# 内容"));
    }

    @Test
    void createAppendsSuffixWhenSlugCollision() {
        when(repository.save(any(WikiPageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.countByWorkspaceIdAndSlug("ws-1", "hello")).thenReturn(1L);
        when(repository.countByWorkspaceIdAndSlug("ws-1", "hello-2")).thenReturn(0L);

        WikiPageDto created = service.create(user, "ws-1", new WikiPageRequest("Hello", "", null, "x"));

        assertEquals("hello-2", created.slug());
    }

    @Test
    void updateReplacesContentAndReIngests() {
        WikiPageEntity existing = new WikiPageEntity("page-1", "ws-1", null, "旧标题", "old",
                "旧内容", "1", Instant.now(), Instant.now());
        when(repository.findById("page-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(WikiPageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WikiPageDto updated = service.update(user, "ws-1", "page-1",
                new WikiPageRequest("新标题", "", null, "新内容"));

        assertEquals("新标题", updated.title());
        assertEquals("新内容", updated.contentMd());
        verify(ingestionService).ingestWikiPage(eq("page-1"), eq("新标题"), eq("1"), eq("ws-1"), eq("新内容"));
        verify(graphService).rebuildForPage(eq("ws-1"), eq("page-1"), eq("新标题"), any(), eq("新内容"));
    }

    @Test
    void deleteRemovesFromIndexAndRepository() {
        WikiPageEntity existing = new WikiPageEntity("page-1", "ws-1", null, "标题", "slug",
                "内容", "1", Instant.now(), Instant.now());
        when(repository.findById("page-1")).thenReturn(Optional.of(existing));

        service.delete(user, "ws-1", "page-1");

        verify(ingestionService).removeWikiPage("ws-1", "page-1");
        verify(graphService).removeForPage("ws-1", "page-1");
        verify(repository).delete(existing);
    }

    @Test
    void rejectsCrossWorkspaceAccess() {
        WikiPageEntity other = new WikiPageEntity("page-1", "ws-other", null, "标题", "slug",
                "内容", "1", Instant.now(), Instant.now());
        when(repository.findById("page-1")).thenReturn(Optional.of(other));

        assertThrows(IllegalArgumentException.class,
                () -> service.get("ws-1", "page-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(user, "ws-1", "page-1", new WikiPageRequest("x", "", null, "y")));
    }

    @Test
    void listReturnsFlatDtos() {
        when(repository.findByWorkspaceIdOrderByTitleAsc("ws-1")).thenReturn(List.of(
                new WikiPageEntity("a", "ws-1", null, "Alpha", "alpha", "", "1", Instant.now(), Instant.now()),
                new WikiPageEntity("b", "ws-1", "a", "Beta", "beta", "", "1", Instant.now(), Instant.now())));
        assertEquals(2, service.list("ws-1").size());
    }

    @Test
    void importFromFileUsesParsedTitleAndContent() {
        MultipartFile file = Mockito.mock(MultipartFile.class);
        Mockito.lenient().when(file.getOriginalFilename()).thenReturn("report.pdf");
        when(ingestionService.parseUploadForWiki(file))
                .thenReturn(new ParsedDocument("pdf", "# 季度报告\n\n正文", "季度报告", List.of()));

        WikiImportContent result = service.importFromFile(user, "ws-1", file);

        assertEquals("季度报告", result.title());
        assertEquals("# 季度报告\n\n正文", result.contentMd());
        assertEquals("report.pdf", result.fileName());
    }

    @Test
    void importFromFileFallsBackToFileNameWhenTitleBlank() {
        MultipartFile file = Mockito.mock(MultipartFile.class);
        Mockito.lenient().when(file.getOriginalFilename()).thenReturn("meeting-notes.txt");
        when(ingestionService.parseUploadForWiki(file))
                .thenReturn(new ParsedDocument("txt", "纯文本", "", List.of()));

        WikiImportContent result = service.importFromFile(user, "ws-1", file);

        assertEquals("meeting-notes", result.title());
        assertEquals("纯文本", result.contentMd());
    }
}
