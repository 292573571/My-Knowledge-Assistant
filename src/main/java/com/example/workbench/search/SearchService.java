package com.example.workbench.search;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.SearchTokenizer;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全局统一搜索：一次查询同时命中文档切片与 wiki 笔记
 * （两者都投影在 document_chunks，wiki 类目为 WIKI_PAGE）。
 * 显式排除 LEARNING_RECORD（学习记录不出现在知识搜索里）。
 */
@Service
public class SearchService {

    private static final Pattern WIKI_PAGE_PATH = Pattern.compile("^docs/wiki/([^/]+)/([0-9a-fA-F-]{36})\\.md$");
    private static final int MAX_LIMIT = 30;

    private final EntityManager entityManager;
    private final WorkspaceService workspaceService;
    private final SearchTokenizer searchTokenizer;

    public SearchService(EntityManager entityManager, WorkspaceService workspaceService, SearchTokenizer searchTokenizer) {
        this.entityManager = entityManager;
        this.workspaceService = workspaceService;
        this.searchTokenizer = searchTokenizer;
    }

    public record SearchHit(String type, String title, String snippet, Integer pageNumber, String headingPath,
                            String documentId, String category, String wikiPageId, double score) { }

    @Transactional(readOnly = true)
    public List<SearchHit> search(AppUser user, String query, String workspaceId, Integer limit) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = limit == null || limit < 1 ? 12 : Math.min(limit, MAX_LIMIT);
        WorkspaceAccessContext workspace = workspaceService.access(user, workspaceId);
        Set<String> readable = workspaceService.effectiveReadableWorkspaceIds(user, workspace.workspaceId());

        List<String> terms = searchTokenizer.words(normalized);
        if (terms.isEmpty()) {
            terms = List.of(normalized);
        }
        List<SearchHit> hits = ftsSearch(normalized, terms, workspace, readable, effectiveLimit);
        if (hits.isEmpty()) {
            hits = ilikeSearch(normalized, workspace, readable, effectiveLimit);
        }
        return hits;
    }

    private List<SearchHit> ftsSearch(String query, List<String> terms, WorkspaceAccessContext workspace,
                                      Set<String> readable, int limit) {
        StringBuilder sql = new StringBuilder("""
                select c.file_name, c.title, c.content, c.page_number, c.heading_path,
                       c.document_id, c.category, c.path,
                       ts_rank(c.search_vector, websearch_to_tsquery('simple', :ftsQuery)) as rank
                from document_chunks c
                where c.category <> 'LEARNING_RECORD' and (
                """);
        appendVisibility(sql, workspace, readable);
        sql.append(") and c.search_vector @@ websearch_to_tsquery('simple', :ftsQuery) ");
        sql.append("order by rank desc, c.document_id, c.chunk_index limit :limit");

        var nativeQuery = entityManager.createNativeQuery(sql.toString());
        nativeQuery.setParameter("ftsQuery", String.join(" OR ", terms));
        nativeQuery.setParameter("limit", limit * 3);
        bindVisibility(nativeQuery, workspace, readable);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery.getResultList();
        return toHits(rows, terms, limit);
    }

    private List<SearchHit> ilikeSearch(String query, WorkspaceAccessContext workspace,
                                        Set<String> readable, int limit) {
        StringBuilder sql = new StringBuilder("""
                select c.file_name, c.title, c.content, c.page_number, c.heading_path,
                       c.document_id, c.category, c.path, 0.0 as rank
                from document_chunks c
                where c.category <> 'LEARNING_RECORD' and (
                """);
        appendVisibility(sql, workspace, readable);
        sql.append(") and position(lower(:raw) in lower(c.search_text)) > 0 ");
        sql.append("order by c.document_id, c.chunk_index limit :limit");

        var nativeQuery = entityManager.createNativeQuery(sql.toString());
        nativeQuery.setParameter("raw", query.toLowerCase());
        nativeQuery.setParameter("limit", limit * 3);
        bindVisibility(nativeQuery, workspace, readable);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery.getResultList();
        return toHits(rows, List.of(query), limit);
    }

    private void appendVisibility(StringBuilder sql, WorkspaceAccessContext workspace, Set<String> readable) {
        boolean scoped = readable != null && !readable.isEmpty();
        sql.append("c.visibility = 'PUBLIC'");
        if (workspace.userId() != null && !workspace.userId().isBlank()) {
            sql.append(" or (c.visibility = 'PRIVATE' and c.owner_user_id = :ownerUserId");
            if (scoped) {
                sql.append(" and c.workspace_id in (:workspaceIds)");
            }
            sql.append(")");
        }
        if (scoped) {
            sql.append(" or (c.visibility = 'WORKSPACE' and c.workspace_id in (:workspaceIds))");
        }
    }

    private void bindVisibility(jakarta.persistence.Query nativeQuery, WorkspaceAccessContext workspace, Set<String> readable) {
        boolean scoped = readable != null && !readable.isEmpty();
        if (workspace.userId() != null && !workspace.userId().isBlank()) {
            nativeQuery.setParameter("ownerUserId", workspace.userId());
        }
        if (scoped) {
            nativeQuery.setParameter("workspaceIds", new ArrayList<>(readable));
        }
    }

    private List<SearchHit> toHits(List<Object[]> rows, List<String> terms, int limit) {
        // 同一文档只保留得分最高的一条，避免整份文档刷屏
        Map<String, SearchHit> byDocument = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String fileName = asString(row[0]);
            String title = asString(row[1]);
            String content = asString(row[2]);
            Integer pageNumber = row[3] == null ? null : ((Number) row[3]).intValue();
            String headingPath = asString(row[4]);
            String documentId = asString(row[5]);
            String category = asString(row[6]);
            String wikiPageId = wikiPageId(row[7]);
            double rank = row[8] == null ? 0.0 : ((Number) row[8]).doubleValue();
            SearchHit hit = new SearchHit(
                    "WIKI_PAGE".equals(category) ? "WIKI" : "DOCUMENT",
                    displayTitle(title, fileName),
                    snippet(content, terms),
                    pageNumber != null && pageNumber > 0 ? pageNumber : null,
                    headingPath, documentId, category, wikiPageId, rank);
            byDocument.putIfAbsent(documentId, hit);
            if (byDocument.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(byDocument.values());
    }

    private String wikiPageId(Object path) {
        if (path == null) return null;
        Matcher matcher = WIKI_PAGE_PATH.matcher(path.toString());
        return matcher.matches() ? matcher.group(2) : null;
    }

    private String displayTitle(String title, String fileName) {
        String candidate = title != null && !title.isBlank() ? title.strip() : fileName;
        return candidate == null || candidate.isBlank() ? "未命名" : candidate;
    }

    private String snippet(String content, List<String> terms) {
        if (content == null || content.isBlank()) return "";
        String lowered = content.toLowerCase();
        int position = -1;
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            position = lowered.indexOf(term.toLowerCase());
            if (position >= 0) break;
        }
        int from = Math.max(0, position < 0 ? 0 : position - 40);
        int to = Math.min(content.length(), from + 160);
        String text = content.substring(from, to).replaceAll("\\s+", " ").strip();
        return (from > 0 ? "…" : "") + text + (to < content.length() ? "…" : "");
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
