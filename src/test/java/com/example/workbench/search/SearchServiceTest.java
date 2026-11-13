package com.example.workbench.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.SearchTokenizer;
import com.example.workbench.search.SearchService.SearchHit;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SearchServiceTest {

    private EntityManager entityManager;
    private WorkspaceService workspaceService;
    private SearchService service;
    private Query query;

    private final AppUser user = new AppUser("alice", "Alice", "hash");

    @BeforeEach
    void setUp() {
        entityManager = Mockito.mock(EntityManager.class);
        workspaceService = Mockito.mock(WorkspaceService.class);
        query = Mockito.mock(Query.class);
        when(query.setParameter(anyString(), Mockito.any())).thenReturn(query);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(workspaceService.access(Mockito.eq(user), anyString()))
                .thenReturn(new WorkspaceAccessContext("user-1", "team-1", WorkspaceRole.EDITOR));
        when(workspaceService.effectiveReadableWorkspaceIds(user, "team-1")).thenReturn(Set.of("team-1"));
        service = new SearchService(entityManager, workspaceService, new SearchTokenizer());
    }

    @Test
    void returnsEmptyForBlankQuery() {
        assertThat(service.search(user, "  ", "team-1", 10)).isEmpty();
        Mockito.verifyNoInteractions(entityManager);
    }

    @Test
    void mapsWikiPagePathToPageIdAndType() {
        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[]{
                "deep-dive.md", "MyBatis 深入", "缓存相关内容", 0, "MyBatis > 缓存",
                "hash123", "WIKI_PAGE", "docs/wiki/team-1/0f2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.md", 0.8d
        }));

        List<SearchService.SearchHit> hits = service.search(user, "mybatis 缓存", "team-1", 10);

        assertThat(hits).hasSize(1);
        SearchHit hit = hits.get(0);
        assertThat(hit.type()).isEqualTo("WIKI");
        assertThat(hit.wikiPageId()).isEqualTo("0f2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
        assertThat(hit.title()).isEqualTo("MyBatis 深入");
    }

    @Test
    void mapsDocumentHitAndDeduplicatesByDocument() {
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"a.pdf", null, "第一段包含部署步骤", 2, "部署", "doc-a", "DOCUMENT", "docs/a.pdf", 0.9d},
                new Object[]{"a.pdf", null, "第二段也提到部署", 3, "部署", "doc-a", "DOCUMENT", "docs/a.pdf", 0.5d},
                new Object[]{"b.xlsx", null, "其他内容", 0, "", "doc-b", "DOCUMENT", "docs/b.xlsx", 0.4d}
        ));

        List<SearchService.SearchHit> hits = service.search(user, "部署", "team-1", 10);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).documentId()).isEqualTo("doc-a");
        assertThat(hits.get(0).pageNumber()).isEqualTo(2);
        assertThat(hits.get(0).snippet()).contains("第一段");
        assertThat(hits.get(1).documentId()).isEqualTo("doc-b");
        assertThat(hits.get(1).pageNumber()).isNull();
    }

    @Test
    void bindsVisibilityParametersIncludingOwnerAndWorkspaces() {
        when(query.getResultList()).thenReturn(List.of());

        service.search(user, "部署", "team-1", 10);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(entityManager, Mockito.times(2)).createNativeQuery(sql.capture());
        assertThat(sql.getAllValues().get(0)).contains("c.category <> 'LEARNING_RECORD'");
        assertThat(sql.getAllValues().get(0)).contains("c.visibility = 'PUBLIC'");
        assertThat(sql.getAllValues().get(0)).contains("owner_user_id = :ownerUserId");
        assertThat(sql.getAllValues().get(0)).contains("c.workspace_id in (:workspaceIds)");
        Mockito.verify(query, Mockito.times(2)).setParameter("ownerUserId", "user-1");
        Mockito.verify(query, Mockito.times(2)).setParameter("workspaceIds", List.of("team-1"));
    }

    @Test
    void fallsBackToIlikeWhenFtsReturnsNothing() {
        Query ftsQuery = Mockito.mock(Query.class);
        Query ilikeQuery = Mockito.mock(Query.class);
        when(ftsQuery.setParameter(anyString(), Mockito.any())).thenReturn(ftsQuery);
        when(ilikeQuery.setParameter(anyString(), Mockito.any())).thenReturn(ilikeQuery);
        when(entityManager.createNativeQuery(anyString())).thenReturn(ftsQuery, ilikeQuery);
        when(ftsQuery.getResultList()).thenReturn(List.of());
        when(ilikeQuery.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[]{
                "c.md", "缓存说明", "Redis 缓存策略", 0, "", "doc-c", "DOCUMENT", "docs/c.md", 0.0d
        }));

        List<SearchService.SearchHit> hits = service.search(user, "缓存策略", "team-1", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).documentId()).isEqualTo("doc-c");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(entityManager, Mockito.times(2)).createNativeQuery(sql.capture());
        assertThat(sql.getAllValues().get(1)).contains("position(lower(:raw) in lower(c.search_text)) > 0");
    }
}
