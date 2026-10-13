package com.example.workbench.wiki;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.DocumentIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * wiki 页面的领域服务：负责 CRUD、层级（parent_id）、slug 唯一性，
 * 并在每次保存后将页面 Markdown 投影进 RAG 检索链路（WIKI_PAGE 类目）。
 */
@Service
public class WikiPageService {

    private final WikiPageRepository repository;
    private final DocumentIngestionService ingestionService;

    public WikiPageService(WikiPageRepository repository, DocumentIngestionService ingestionService) {
        this.repository = repository;
        this.ingestionService = ingestionService;
    }

    @Transactional
    public WikiPageDto create(AppUser user, String workspaceId, WikiPageRequest request) {
        String title = requireTitle(request.title());
        String slug = resolveSlug(workspaceId, request.slug(), title, null);
        Instant now = Instant.now();
        WikiPageEntity entity = new WikiPageEntity(
                UUID.randomUUID().toString(),
                workspaceId,
                normalizeParentId(request.parentId()),
                title,
                slug,
                request.contentMd() == null ? "" : request.contentMd(),
                String.valueOf(user.getId()),
                now,
                now
        );
        repository.save(entity);
        ingest(entity);
        return WikiPageDto.from(entity);
    }

    @Transactional
    public WikiPageDto update(AppUser user, String workspaceId, String pageId, WikiPageRequest request) {
        WikiPageEntity entity = requireOwnedPage(workspaceId, pageId);
        String title = requireTitle(request.title());
        entity.setTitle(title);
        if (request.slug() != null && !request.slug().isBlank()) {
            entity.setSlug(resolveSlug(workspaceId, request.slug(), title, pageId));
        }
        entity.setParentId(normalizeParentId(request.parentId()));
        entity.setContentMd(request.contentMd() == null ? "" : request.contentMd());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        ingest(entity);
        return WikiPageDto.from(entity);
    }

    @Transactional
    public void delete(AppUser user, String workspaceId, String pageId) {
        WikiPageEntity entity = requireOwnedPage(workspaceId, pageId);
        ingestionService.removeWikiPage(workspaceId, pageId);
        repository.delete(entity);
    }

    public List<WikiPageDto> list(String workspaceId) {
        return repository.findByWorkspaceIdOrderByTitleAsc(workspaceId).stream()
                .map(WikiPageDto::from)
                .toList();
    }

    public WikiPageDto get(String workspaceId, String pageId) {
        return WikiPageDto.from(requireOwnedPage(workspaceId, pageId));
    }

    private void ingest(WikiPageEntity entity) {
        if (entity.getContentMd() == null || entity.getContentMd().isBlank()) {
            return;
        }
        ingestionService.ingestWikiPage(
                entity.getId(),
                entity.getTitle(),
                entity.getOwnerId(),
                entity.getWorkspaceId(),
                entity.getContentMd()
        );
    }

    private WikiPageEntity requireOwnedPage(String workspaceId, String pageId) {
        WikiPageEntity entity = repository.findById(pageId)
                .orElseThrow(() -> new IllegalArgumentException("Wiki page not found: " + pageId));
        if (!entity.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Wiki page not found: " + pageId);
        }
        return entity;
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Wiki page title cannot be empty");
        }
        return title.trim();
    }

    private String normalizeParentId(String parentId) {
        return (parentId == null || parentId.isBlank()) ? null : parentId;
    }

    private String resolveSlug(String workspaceId, String desired, String title, String ignoreId) {
        String base = slugify(desired == null || desired.isBlank() ? title : desired);
        if (base.isBlank()) {
            base = "untitled";
        }
        if (base.length() > 200) {
            base = base.substring(0, 200).replaceAll("-+$", "");
        }
        String candidate = base;
        int suffix = 2;
        while (ignoreId == null
                ? repository.countByWorkspaceIdAndSlug(workspaceId, candidate) > 0
                : repository.countByWorkspaceIdAndSlugAndIdNot(workspaceId, candidate, ignoreId) > 0) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String slugify(String input) {
        if (input == null) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }
}
