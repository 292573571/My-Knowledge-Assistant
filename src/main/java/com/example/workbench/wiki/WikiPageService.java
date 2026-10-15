package com.example.workbench.wiki;

import com.example.workbench.auth.AppUser;
import com.example.workbench.kg.KnowledgeGraphService;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.ParsedDocument;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * wiki 页面的领域服务：负责 CRUD、层级（parent_id）、slug 唯一性，
 * 并在每次保存后将页面 Markdown 投影进 RAG 检索链路（WIKI_PAGE 类目）。
 */
@Service
public class WikiPageService {

    private final WikiPageRepository repository;
    private final DocumentIngestionService ingestionService;
    private final KnowledgeGraphService graphService;

    public WikiPageService(WikiPageRepository repository, DocumentIngestionService ingestionService,
                           KnowledgeGraphService graphService) {
        this.repository = repository;
        this.ingestionService = ingestionService;
        this.graphService = graphService;
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
        graphService.removeForPage(workspaceId, pageId);
        repository.delete(entity);
    }

    public List<WikiPageDto> list(String workspaceId) {
        return repository.findByWorkspaceIdOrderByTitleAsc(workspaceId).stream()
                .map(WikiPageDto::from)
                .toList();
    }

    /**
     * 将上传的 PDF / Word / 文本等文件解析为 wiki 页面草稿。
     *
     * <p>不直接落库，仅返回解析出的标题与 Markdown 正文，交由前端填入编辑器；
     * 用户确认保存时再走 {@link #create} 投影进 RAG 检索链路，形成一张 WIKI_PAGE。
     * 解析与校验委托 DocumentIngestionService，复用既有的扩展名白名单与 50MB 限制。
     *
     * @param user 操作人
     * @param workspaceId 知识空间标识
     * @param file 上传文件
     * @return 解析后的导入草稿
     */
    public WikiImportContent importFromFile(AppUser user, String workspaceId, MultipartFile file) {
        ParsedDocument parsed = ingestionService.parseUploadForWiki(file);
        String originalFileName = file.getOriginalFilename();
        String baseName = originalFileName == null ? "" : originalFileName.replaceAll("\\.[^.]+$", "");
        String title = (parsed.title() != null && !parsed.title().isBlank())
                ? parsed.title().trim()
                : (baseName.isBlank() ? "未命名导入" : baseName.trim());
        String contentMd = parsed.content() == null ? "" : parsed.content();
        return new WikiImportContent(originalFileName, title, contentMd);
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
        graphService.rebuildForPage(
                entity.getWorkspaceId(),
                entity.getId(),
                entity.getTitle(),
                entity.getParentId(),
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
