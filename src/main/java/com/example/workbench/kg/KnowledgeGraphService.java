package com.example.workbench.kg;

import com.example.workbench.wiki.WikiPageEntity;
import com.example.workbench.wiki.WikiPageRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识图谱的确定性抽取与维护。
 *
 * <p>完全不依赖 NLP / 大模型：以 wiki 页为骨架，依据页面层级与用户显式书写的
 * {@code [[页面名]]} / {@code [[页面名|别名]]} 链接构建图谱。链接目标能解析到某张页则连 WIKI_LINK，
 * 否则落成 TERM 概念节点并连 MENTIONS。图谱由 WikiPageService 在保存/删除时驱动，幂等重建。
 */
@Service
public class KnowledgeGraphService {

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]");

    private final KgEntityRepository entityRepository;
    private final KgRelationRepository relationRepository;
    private final WikiPageRepository wikiPageRepository;

    public KnowledgeGraphService(KgEntityRepository entityRepository,
                                 KgRelationRepository relationRepository,
                                 WikiPageRepository wikiPageRepository) {
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
        this.wikiPageRepository = wikiPageRepository;
    }

    /**
     * 依据一张 wiki 页重建它在图谱中的全部贡献（节点 + 关系），幂等。
     *
     * <p>先按 evidence_source_id 删除该页曾产生的所有关系，再重新抽取。WIKI_PAGE 节点本身做 upsert，
     * 因此重命名标题会同步更新节点名；TERM 节点跨页共享，不随单页删除而消失。
     */
    @Transactional
    public void rebuildForPage(String workspaceId, String pageId, String title,
                               String parentId, String contentMd) {
        relationRepository.deleteByWorkspaceIdAndEvidenceSourceId(workspaceId, pageId);

        KgEntity pageEntity = upsertWikiPageEntity(workspaceId, pageId, title);

        if (parentId != null && !parentId.isBlank()) {
            wikiPageRepository.findById(parentId).ifPresent(parentPage -> {
                KgEntity parentEntity = upsertWikiPageEntity(workspaceId, parentPage.getId(), parentPage.getTitle());
                upsertRelation(workspaceId, pageEntity.getId(), parentEntity.getId(),
                        KgTypes.REL_PARENT_CHILD, 1, pageId);
            });
        }

        // 确保以本页为父的层级边存在，与页面创建顺序无关（子页可能先于父页被保存）
        for (WikiPageEntity child : wikiPageRepository.findByParentId(pageId)) {
            String childEntityId = KgTypes.wikiEntityId(child.getId());
            if (entityRepository.existsById(childEntityId)) {
                upsertRelation(workspaceId, childEntityId, pageEntity.getId(),
                        KgTypes.REL_PARENT_CHILD, 1, child.getId());
            }
        }

        if (contentMd != null && !contentMd.isBlank()) {
            for (String target : parseLinkTargets(contentMd)) {
                if (target.equalsIgnoreCase(title)) {
                    continue; // 自引用忽略
                }
                Optional<WikiPageEntity> linked = resolveWikiPage(workspaceId, target);
                if (linked.isPresent()) {
                    KgEntity targetEntity = upsertWikiPageEntity(workspaceId, linked.get().getId(), linked.get().getTitle());
                    upsertRelation(workspaceId, pageEntity.getId(), targetEntity.getId(),
                            KgTypes.REL_WIKI_LINK, 1, pageId);
                } else {
                    KgEntity term = upsertTermEntity(workspaceId, target);
                    upsertRelation(workspaceId, pageEntity.getId(), term.getId(),
                            KgTypes.REL_MENTIONS, 1, pageId);
                }
            }
        }
    }

    /**
     * 删除一张 wiki 页时清理它在图谱中的贡献：删除其产生的所有关系，删除其 WIKI_PAGE 节点
     * （级联删除指向/来自它的边），并清理因此产生的孤立 TERM 节点。
     */
    @Transactional
    public void removeForPage(String workspaceId, String pageId) {
        relationRepository.deleteByWorkspaceIdAndEvidenceSourceId(workspaceId, pageId);
        entityRepository.findById(KgTypes.wikiEntityId(pageId)).ifPresent(entityRepository::delete);
        entityRepository.deleteOrphanTerms(workspaceId);
    }

    /**
     * 取某 wiki 页的 1 跳邻接 wiki 页集合（用于 RAG 图谱感知扩召回）：
     * 出边中的 WIKI_LINK 目标页与 PARENT_CHILD 子页，以及入边中的 PARENT_CHILD 父页。
     * 返回的是 pageId 列表（剥离 wiki: 前缀），不含自身。
     */
    public Set<String> neighborWikiPageIds(String workspaceId, String pageId) {
        String pageEntityId = KgTypes.wikiEntityId(pageId);
        Set<String> neighbors = new HashSet<>();
        for (KgRelation relation : relationRepository.findByWorkspaceIdAndSourceEntityId(workspaceId, pageEntityId)) {
            if (KgTypes.REL_WIKI_LINK.equals(relation.getRelationType())
                    || KgTypes.REL_PARENT_CHILD.equals(relation.getRelationType())) {
                String neighbor = toPageId(relation.getTargetEntityId());
                if (neighbor != null) {
                    neighbors.add(neighbor);
                }
            }
        }
        for (KgRelation relation : relationRepository.findByWorkspaceIdAndTargetEntityId(workspaceId, pageEntityId)) {
            if (KgTypes.REL_PARENT_CHILD.equals(relation.getRelationType())) {
                String parent = toPageId(relation.getSourceEntityId());
                if (parent != null) {
                    neighbors.add(parent);
                }
            }
        }
        neighbors.remove(pageId);
        return neighbors;
    }

    private static String toPageId(String entityId) {
        if (entityId != null && entityId.startsWith(KgTypes.WIKI_ENTITY_PREFIX)) {
            return entityId.substring(KgTypes.WIKI_ENTITY_PREFIX.length());
        }
        return null;
    }

    private KgEntity upsertWikiPageEntity(String workspaceId, String pageId, String title) {
        String id = KgTypes.wikiEntityId(pageId);
        KgEntity entity = entityRepository.findById(id).orElse(new KgEntity());
        Instant now = Instant.now();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        entity.setEntityType(KgTypes.ENTITY_WIKI_PAGE);
        entity.setSourceKind(KgTypes.ENTITY_WIKI_PAGE);
        entity.setSourceId(pageId);
        entity.setName(title != null && !title.isBlank() ? title : pageId);
        entity.setMentionCount(1);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        return entityRepository.save(entity);
    }

    private KgEntity upsertTermEntity(String workspaceId, String termName) {
        String id = KgTypes.termEntityId(termSlug(termName));
        KgEntity entity = entityRepository.findById(id).orElse(new KgEntity());
        Instant now = Instant.now();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        entity.setEntityType(KgTypes.ENTITY_TERM);
        entity.setSourceKind(KgTypes.ENTITY_TERM);
        entity.setSourceId(termName);
        entity.setName(termName);
        entity.setMentionCount(1);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        return entityRepository.save(entity);
    }

    private void upsertRelation(String workspaceId, String sourceId, String targetId,
                                String type, int weight, String evidence) {
        if (sourceId.equals(targetId)) {
            return; // 不自环
        }
        if (relationRepository.findByWorkspaceIdAndSourceEntityIdAndTargetEntityIdAndRelationType(
                workspaceId, sourceId, targetId, type).isPresent()) {
            return;
        }
        KgRelation relation = new KgRelation();
        relation.setId(UUID.randomUUID().toString());
        relation.setWorkspaceId(workspaceId);
        relation.setSourceEntityId(sourceId);
        relation.setTargetEntityId(targetId);
        relation.setRelationType(type);
        relation.setWeight(weight);
        relation.setEvidenceSourceId(evidence);
        relation.setCreatedAt(Instant.now());
        relationRepository.save(relation);
    }

    private Optional<WikiPageEntity> resolveWikiPage(String workspaceId, String target) {
        Optional<WikiPageEntity> byTitle = wikiPageRepository.findByWorkspaceIdAndTitleIgnoreCase(workspaceId, target.trim());
        if (byTitle.isPresent()) {
            return byTitle;
        }
        return wikiPageRepository.findByWorkspaceIdAndSlug(workspaceId, termSlug(target));
    }

    private Set<String> parseLinkTargets(String contentMd) {
        Set<String> targets = new HashSet<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(contentMd);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            if (!raw.isEmpty()) {
                targets.add(raw);
            }
        }
        return targets;
    }

    private String termSlug(String input) {
        if (input == null) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }
}
