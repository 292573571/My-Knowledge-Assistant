package com.example.workbench.kg;

import com.example.workbench.wiki.WikiPageEntity;
import com.example.workbench.wiki.WikiPageRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动期一次性回填：P3 上线前已存在的 wiki 页不会触发 rebuildForPage，部署后图谱会为空。
 * 仅对「有 wiki 页但尚未建过图谱」的知识空间跑一次，把存量页抽进图谱；幂等，
 * 不影响后续通过 WikiPageService 实时维护的图谱。
 */
@Component
@Order(100)
public class KnowledgeGraphBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphBackfill.class);

    private final WikiPageRepository wikiRepository;
    private final KgEntityRepository entityRepository;
    private final KnowledgeGraphService graphService;

    public KnowledgeGraphBackfill(WikiPageRepository wikiRepository,
                                  KgEntityRepository entityRepository,
                                  KnowledgeGraphService graphService) {
        this.wikiRepository = wikiRepository;
        this.entityRepository = entityRepository;
        this.graphService = graphService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<WikiPageEntity> pages = wikiRepository.findAll();
            Map<String, List<WikiPageEntity>> byWorkspace = pages.stream()
                    .collect(Collectors.groupingBy(WikiPageEntity::getWorkspaceId));
            for (Map.Entry<String, List<WikiPageEntity>> entry : byWorkspace.entrySet()) {
                String workspaceId = entry.getKey();
                if (entityRepository.countByWorkspaceId(workspaceId) > 0) {
                    continue; // 已建过图，跳过避免每次重启重复回填
                }
                for (WikiPageEntity page : entry.getValue()) {
                    try {
                        graphService.rebuildForPage(workspaceId, page.getId(), page.getTitle(),
                                page.getParentId(), page.getContentMd());
                    } catch (Exception ex) {
                        log.warn("知识图谱回填单页失败 workspace={} page={}: {}",
                                workspaceId, page.getId(), ex.getMessage());
                    }
                }
                log.info("知识图谱回填完成 workspace={} pages={}", workspaceId, entry.getValue().size());
            }
        } catch (Exception ex) {
            log.warn("知识图谱回填异常（可忽略，后续编辑页面会自动建图）: {}", ex.getMessage());
        }
    }
}
