package com.example.workbench.kg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.workbench.wiki.WikiPageEntity;
import com.example.workbench.wiki.WikiPageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeGraphServiceTest {

    @Mock
    private KgEntityRepository entityRepository;

    @Mock
    private KgRelationRepository relationRepository;

    @Mock
    private WikiPageRepository wikiPageRepository;

    @InjectMocks
    private KnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        // 默认：关系不存在（允许 upsert 新建），实体查找返回空（视为新建）。
        // 部分用例不会用到全部桩，按项目惯例用 lenient 避免 strict stubbing 报错。
        org.mockito.Mockito.lenient().when(relationRepository
                .findByWorkspaceIdAndSourceEntityIdAndTargetEntityIdAndRelationType(
                        any(), any(), any(), any())).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(entityRepository.findById(any())).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(entityRepository.save(any(KgEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(relationRepository.save(any(KgRelation.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(wikiPageRepository.findByParentId(any())).thenReturn(List.of());
    }

    private KgRelation capturedRelation() {
        ArgumentCaptor<KgRelation> captor = ArgumentCaptor.forClass(KgRelation.class);
        verify(relationRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void wikiLinkToExistingPageCreatesWikiLinkEdge() {
        when(wikiPageRepository.findByWorkspaceIdAndTitleIgnoreCase("ws", "Beta"))
                .thenReturn(Optional.of(new WikiPageEntity("p2", "ws", null, "Beta", "beta", "", "1", Instant.now(), Instant.now())));

        service.rebuildForPage("ws", "p1", "Alpha", null, "参见 [[Beta]] 了解更多");

        verify(relationRepository).deleteByWorkspaceIdAndEvidenceSourceId("ws", "p1");
        KgRelation rel = capturedRelation();
        assertEquals("wiki:p1", rel.getSourceEntityId());
        assertEquals("wiki:p2", rel.getTargetEntityId());
        assertEquals(KgTypes.REL_WIKI_LINK, rel.getRelationType());
        assertEquals("p1", rel.getEvidenceSourceId());
    }

    @Test
    void wikiLinkToMissingPageCreatesTermNodeAndMentionsEdge() {
        when(wikiPageRepository.findByWorkspaceIdAndTitleIgnoreCase("ws", "量子计算")).thenReturn(Optional.empty());
        when(wikiPageRepository.findByWorkspaceIdAndSlug("ws", "量子计算")).thenReturn(Optional.empty());

        service.rebuildForPage("ws", "p1", "Alpha", null, "研究 [[量子计算]] 的应用");

        KgRelation rel = capturedRelation();
        assertEquals("wiki:p1", rel.getSourceEntityId());
        assertEquals(KgTypes.termEntityId("量子计算"), rel.getTargetEntityId());
        assertEquals(KgTypes.REL_MENTIONS, rel.getRelationType());
    }

    @Test
    void linkAliasUsesTargetBeforePipe() {
        when(wikiPageRepository.findByWorkspaceIdAndTitleIgnoreCase("ws", "Beta")).thenReturn(Optional.empty());
        when(wikiPageRepository.findByWorkspaceIdAndSlug("ws", "beta")).thenReturn(Optional.empty());

        service.rebuildForPage("ws", "p1", "Alpha", null, "[[Beta|点击这里]]");

        KgRelation rel = capturedRelation();
        assertEquals(KgTypes.termEntityId("beta"), rel.getTargetEntityId());
    }

    @Test
    void parentIdCreatesParentChildEdge() {
        when(wikiPageRepository.findById("p0"))
                .thenReturn(Optional.of(new WikiPageEntity("p0", "ws", null, "Root", "root", "", "1", Instant.now(), Instant.now())));

        service.rebuildForPage("ws", "p1", "Alpha", "p0", "内容");

        KgRelation rel = capturedRelation();
        assertEquals("wiki:p1", rel.getSourceEntityId());
        assertEquals("wiki:p0", rel.getTargetEntityId());
        assertEquals(KgTypes.REL_PARENT_CHILD, rel.getRelationType());
    }

    @Test
    void selfLinkIsIgnored() {
        service.rebuildForPage("ws", "p1", "Alpha", null, "[[Alpha]] 自引用");

        verify(relationRepository, never()).save(any(KgRelation.class));
    }

    @Test
    void removeForPageCleansRelationsEntityAndOrphans() {
        when(entityRepository.findById("wiki:p1"))
                .thenReturn(Optional.of(new KgEntity() {{
                    setId("wiki:p1");
                }}));

        service.removeForPage("ws", "p1");

        verify(relationRepository).deleteByWorkspaceIdAndEvidenceSourceId("ws", "p1");
        verify(entityRepository).delete(any(KgEntity.class));
        verify(entityRepository).deleteOrphanTerms("ws");
    }

    @Test
    void idempotentRebuildDeletesOldEvidenceFirst() {
        service.rebuildForPage("ws", "p1", "Alpha", null, "[[Beta]]");
        service.rebuildForPage("ws", "p1", "Alpha", null, "[[Beta]]");

        // 每次重建先按 evidence 清掉该页旧关系，再重新抽取，因此重复运行安全（DB 唯一约束兜底）
        verify(relationRepository, times(2)).deleteByWorkspaceIdAndEvidenceSourceId("ws", "p1");
    }

    @Test
    void neighborWikiPageIdsIncludesLinksChildrenAndParent() {
        KgRelation link = new KgRelation();
        link.setSourceEntityId("wiki:p1");
        link.setTargetEntityId("wiki:p2");
        link.setRelationType(KgTypes.REL_WIKI_LINK);
        KgRelation child = new KgRelation();
        child.setSourceEntityId("wiki:p1");
        child.setTargetEntityId("wiki:p3");
        child.setRelationType(KgTypes.REL_PARENT_CHILD);
        KgRelation parent = new KgRelation();
        parent.setSourceEntityId("wiki:p0");
        parent.setTargetEntityId("wiki:p1");
        parent.setRelationType(KgTypes.REL_PARENT_CHILD);
        when(relationRepository.findByWorkspaceIdAndSourceEntityId("ws", "wiki:p1"))
                .thenReturn(List.of(link, child));
        when(relationRepository.findByWorkspaceIdAndTargetEntityId("ws", "wiki:p1"))
                .thenReturn(List.of(parent));

        Set<String> neighbors = service.neighborWikiPageIds("ws", "p1");

        assertEquals(Set.of("p2", "p3", "p0"), neighbors);
    }

    @Test
    void neighborWikiPageIdsIgnoresTermAndSelfTargets() {
        KgRelation term = new KgRelation();
        term.setSourceEntityId("wiki:p1");
        term.setTargetEntityId("term:xyz");
        term.setRelationType(KgTypes.REL_MENTIONS);
        when(relationRepository.findByWorkspaceIdAndSourceEntityId("ws", "wiki:p1"))
                .thenReturn(List.of(term));

        Set<String> neighbors = service.neighborWikiPageIds("ws", "p1");

        assertTrue(neighbors.isEmpty());
    }

    @Test
    void neighborWikiPageIdsNoRelationsReturnsEmpty() {
        assertTrue(service.neighborWikiPageIds("ws", "p1").isEmpty());
    }
}
