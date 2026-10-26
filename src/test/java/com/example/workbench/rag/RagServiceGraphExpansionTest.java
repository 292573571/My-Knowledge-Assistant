package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.kg.KnowledgeGraphService;
import com.example.workbench.memory.ConversationMemory;
import com.example.workbench.tools.WebSearchService;
import com.example.workbench.workspace.DocumentVisibility;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 图谱扩召回必须在同步与流式两条链路都生效。
 *
 * <p>历史缺陷：扩召回只写在 {@code chatInternal}，而线上问答全部走 {@code stream}，
 * 导致该能力实际从未生效。这两个用例把两条链路口径同时钉住。</p>
 */
class RagServiceGraphExpansionTest {

    private static final String WORKSPACE_ID = "ws-1";

    @Test
    void streamIncludesWikiGraphNeighborChunks() {
        Fixture fixture = fixture();

        RagStreamResponse response = fixture.service.stream(
                new RagChatRequest("conversation-1", WORKSPACE_ID, "缓存穿透和缓存雪崩有什么区别？"));

        assertThat(response.route()).isEqualTo(RagAnswerRoute.LOCAL_KNOWLEDGE);
        assertThat(response.sources()).extracting(RagSource::headingPath)
                .contains("缓存穿透", "缓存雪崩");
    }

    @Test
    void syncChatIncludesWikiGraphNeighborChunks() {
        Fixture fixture = fixture();

        RagChatResponse response = fixture.service.chat(
                new RagChatRequest("conversation-1", WORKSPACE_ID, "缓存穿透和缓存雪崩有什么区别？"));

        assertThat(response.route()).isEqualTo(RagAnswerRoute.LOCAL_KNOWLEDGE);
        assertThat(response.sources()).extracting(RagSource::headingPath)
                .contains("缓存穿透", "缓存雪崩");
    }

    private Fixture fixture() {
        VectorStore vectorStore = mock(VectorStore.class);
        LocalChatClient chatClient = mock(LocalChatClient.class);
        WebSearchService webSearchService = mock(WebSearchService.class);
        RagQualityGate qualityGate = mock(RagQualityGate.class);
        DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
        KnowledgeGraphService knowledgeGraphService = mock(KnowledgeGraphService.class);

        SourceDocument hit = wikiChunk("chunk-1", "page-1", "缓存穿透", 0.12,
                "缓存穿透是指查询一个一定不存在的数据，由于缓存不命中而直接打到数据库。");
        SourceDocument neighbor = wikiChunk("chunk-2", "page-2", "缓存雪崩", 0.0,
                "缓存雪崩是指大量缓存同一时间失效，请求同时打到数据库导致雪崩。");

        when(vectorStore.similaritySearch(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(hit));
        when(knowledgeGraphService.neighborWikiPageIds(WORKSPACE_ID, "page-1"))
                .thenReturn(Set.of("page-2"));
        when(ingestionService.getChunksByWikiPageId(WORKSPACE_ID, "page-2"))
                .thenReturn(List.of(neighbor));
        when(qualityGate.relevantSources(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(qualityGate.approvesAnswer(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(true);
        when(chatClient.stream(anyString(), anyMap()))
                .thenReturn(Flux.just("缓存穿透是查询不存在的数据，缓存雪崩是大量缓存同时失效。"));
        when(chatClient.call(anyString(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(), anyMap()))
                .thenReturn("缓存穿透是查询不存在的数据，缓存雪崩是大量缓存同时失效。");

        RagService service = new RagService(
                ingestionService, vectorStore, chatClient, new ConversationMemory(), webSearchService,
                qualityGate, false, 5, 0.45, "distance", false, false, 4, true, false);
        service.wikiGraphExpansionEnabled = true;
        service.setKnowledgeGraphService(knowledgeGraphService);
        return new Fixture(service);
    }

    private SourceDocument wikiChunk(String id, String pageId, String heading, double score, String content) {
        String path = "docs/wiki/" + WORKSPACE_ID + "/" + pageId + ".md";
        return new SourceDocument(
                id, content, heading, path, path, 0,
                "doc-" + pageId, pageId + ".md", "hash-" + pageId, score, heading, 1,
                0, content.length(), "text-paragraph", DocumentCategory.WIKI_PAGE,
                "", WORKSPACE_ID, DocumentVisibility.PUBLIC);
    }

    private record Fixture(RagService service) {
    }
}
