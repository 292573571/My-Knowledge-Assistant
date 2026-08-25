package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class ChromaVectorStoreAdapter implements ScopedVectorStore {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorStoreAdapter.class);

    private final ObjectProvider<org.springframework.ai.vectorstore.VectorStore> chromaVectorStoreProvider;
    private final ObjectProvider<ChromaApi> chromaApiProvider;
    private final ObjectProvider<ChromaVectorStoreProperties> chromaPropertiesProvider;
    private final InMemoryVectorStore fallbackVectorStore;
    private final Optional<PostgresSparseRetriever> sparseRetriever;

    @Autowired
    public ChromaVectorStoreAdapter(
            ObjectProvider<org.springframework.ai.vectorstore.VectorStore> chromaVectorStoreProvider,
            InMemoryVectorStore fallbackVectorStore,
            ObjectProvider<ChromaApi> chromaApiProvider,
            ObjectProvider<ChromaVectorStoreProperties> chromaPropertiesProvider,
            Optional<PostgresSparseRetriever> sparseRetriever
    ) {
        this.chromaVectorStoreProvider = chromaVectorStoreProvider;
        this.fallbackVectorStore = fallbackVectorStore;
        this.chromaApiProvider = chromaApiProvider;
        this.chromaPropertiesProvider = chromaPropertiesProvider;
        this.sparseRetriever = sparseRetriever;
    }

    ChromaVectorStoreAdapter(
            ObjectProvider<org.springframework.ai.vectorstore.VectorStore> chromaVectorStoreProvider,
            InMemoryVectorStore fallbackVectorStore
    ) {
        this(chromaVectorStoreProvider, fallbackVectorStore, emptyProvider(ChromaApi.class),
                emptyProvider(ChromaVectorStoreProperties.class), Optional.empty());
    }

    public boolean isChromaConfigured() {
        return chromaVectorStoreProvider.getIfAvailable() != null;
    }

    @Override
    public void clear() {
        fallbackVectorStore.clear();
        org.springframework.ai.vectorstore.VectorStore chromaVectorStore = chromaVectorStoreProvider.getIfAvailable();
        if (chromaVectorStore == null) {
            sparseRetriever.ifPresent(PostgresSparseRetriever::clear);
            return;
        }

        try {
            // 全量重建不能依赖当前进程记住的 ID；按写入时必备的 id metadata 清除集合内全部项目向量。
            chromaVectorStore.delete(new FilterExpressionBuilder().ne("id", "").build());
            sparseRetriever.ifPresent(PostgresSparseRetriever::clear);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to clear all documents from Chroma", exception);
        }
    }

    @Override
    public void deleteByIds(List<String> ids) {
        fallbackVectorStore.deleteByIds(ids);

        org.springframework.ai.vectorstore.VectorStore chromaVectorStore = chromaVectorStoreProvider.getIfAvailable();
        if (chromaVectorStore == null || ids.isEmpty()) {
            sparseRetriever.ifPresent(retriever -> retriever.deleteByIds(ids));
            return;
        }

        try {
            chromaVectorStore.delete(ids);
            sparseRetriever.ifPresent(retriever -> retriever.deleteByIds(ids));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to delete documents from Chroma", exception);
        }
    }

    @Override
    public void addAll(List<SourceDocument> documents) {
        fallbackVectorStore.addAll(documents);
        org.springframework.ai.vectorstore.VectorStore chromaVectorStore = chromaVectorStoreProvider.getIfAvailable();
        if (chromaVectorStore == null) {
            log.warn("Spring AI Chroma VectorStore is not available, using in-memory vector store only");
            sparseRetriever.ifPresent(retriever -> retriever.addAll(documents));
            return;
        }

        try {
            chromaVectorStore.add(documents.stream()
                    .map(this::toSpringAiDocument)
                    .toList());
            sparseRetriever.ifPresent(retriever -> retriever.addAll(documents));
        } catch (RuntimeException exception) {
            // 配置了持久向量库时不能把仅内存写入视为成功，否则重启后任务显示成功但文档无法检索。
            throw new IllegalStateException("Failed to write documents to Chroma", exception);
        }
    }

    @Override
    public void replaceAll(List<SourceDocument> documents) {
        clear();
        addAll(documents);
    }

    /**
     * 按精确 chunk ID 从 Chroma 恢复已经持久化的正文，不触发新的向量计算。
     *
     * @param ids chunk ID 列表
     * @return Chroma 中仍存在的文档片段
     */
    public List<SourceDocument> documentsByIds(List<String> ids) {
        ChromaApi api = chromaApiProvider.getIfAvailable();
        ChromaVectorStoreProperties properties = chromaPropertiesProvider.getIfAvailable();
        if (api == null || properties == null || ids.isEmpty()) {
            return List.of();
        }
        try {
            ChromaApi.Collection collection = api.getCollection(
                    properties.getTenantName(), properties.getDatabaseName(), properties.getCollectionName());
            ChromaApi.GetEmbeddingResponse response = api.getEmbeddings(
                    properties.getTenantName(), properties.getDatabaseName(), collection.id(),
                    new ChromaApi.GetEmbeddingsRequest(ids, null, ids.size(), 0,
                            List.of(ChromaApi.QueryRequest.Include.DOCUMENTS,
                                    ChromaApi.QueryRequest.Include.METADATAS)));
            List<String> responseIds = Optional.ofNullable(response.ids()).orElse(List.of());
            List<String> contents = Optional.ofNullable(response.documents()).orElse(List.of());
            List<Map<String, String>> metadata = Optional.ofNullable(response.metadata()).orElse(List.of());
            List<SourceDocument> recovered = new java.util.ArrayList<>();
            for (int index = 0; index < responseIds.size() && index < contents.size(); index++) {
                recovered.add(toSourceDocument(responseIds.get(index), contents.get(index),
                        index < metadata.size() ? metadata.get(index) : Map.of()));
            }
            return recovered;
        } catch (RuntimeException exception) {
            log.warn("Failed to read documents from Chroma by ids count={}", ids.size(), exception);
            return List.of();
        }
    }

    @Override
    public List<SourceDocument> similaritySearch(String query, int topK) {
        return scopedSearch(query, topK, null, null, false);
    }

    @Override
    public List<SourceDocument> similaritySearch(String query, int topK, String ownerUserId, Set<String> readableWorkspaceIds) {
        return scopedSearch(query, topK, ownerUserId, readableWorkspaceIds, true);
    }

    private List<SourceDocument> scopedSearch(String query, int topK, String ownerUserId, Set<String> readableWorkspaceIds,
                                              boolean scoped) {
        org.springframework.ai.vectorstore.VectorStore chromaVectorStore = chromaVectorStoreProvider.getIfAvailable();
        if (chromaVectorStore == null) {
            return fallbackSearch(query, topK, ownerUserId, readableWorkspaceIds, scoped);
        }

        try {
            SearchRequest.Builder request = SearchRequest.builder()
                    .query(query)
                    .topK(topK);
            if (scoped) {
                request.filterExpression(visibilityFilter(ownerUserId, readableWorkspaceIds));
            }
            List<Document> documents = chromaVectorStore.similaritySearch(request.build());

            if (documents == null || documents.isEmpty()) {
                return fallbackSearch(query, topK, ownerUserId, readableWorkspaceIds, scoped);
            }

            return documents.stream()
                    .map(this::toSourceDocument)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("Failed to search Chroma, using in-memory vector store fallback errorType={}", exception.getClass().getSimpleName());
            return fallbackSearch(query, topK, ownerUserId, readableWorkspaceIds, scoped);
        }
    }

    private List<SourceDocument> fallbackSearch(String query, int topK, String ownerUserId, Set<String> readableWorkspaceIds,
                                                 boolean scoped) {
        List<SourceDocument> results = scoped
                ? fallbackVectorStore.similaritySearch(query, topK, ownerUserId, readableWorkspaceIds)
                : fallbackVectorStore.similaritySearch(query, topK);
        // 内存实现返回余弦相似度，适配器统一转换为与生产 Chroma 相同的“越小越好”距离语义。
        return results.stream()
                .map(source -> source.withScore(Math.max(0.0, 1.0 - source.score())))
                .toList();
    }

    /**
     * 构建 Chroma 元数据过滤表达式：
     * <ul>
     *   <li>PUBLIC 文档对所有用户可见；</li>
     *   <li>PRIVATE 文档仅本人可见，且需在可读空间集合内；</li>
     *   <li>WORKSPACE 文档在「有效可读空间集合」内可见（组织可见其全部子孙，团队可见其自身与祖先组织）。</li>
     * </ul>
     */
    private Filter.Expression visibilityFilter(String ownerUserId, Set<String> readableWorkspaceIds) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op allowed = builder.eq("visibility", "PUBLIC");

        if (ownerUserId != null && !ownerUserId.isBlank()) {
            FilterExpressionBuilder.Op privateFilter = builder.and(
                    builder.eq("visibility", "PRIVATE"),
                    builder.eq("ownerUserId", ownerUserId));
            if (readableWorkspaceIds != null && !readableWorkspaceIds.isEmpty()) {
                privateFilter = builder.and(privateFilter, workspaceEqualsAny(builder, readableWorkspaceIds));
            }
            allowed = builder.or(allowed, privateFilter);
        }

        if (readableWorkspaceIds != null && !readableWorkspaceIds.isEmpty()) {
            FilterExpressionBuilder.Op workspaceFilter = builder.and(
                    builder.eq("visibility", "WORKSPACE"),
                    workspaceEqualsAny(builder, readableWorkspaceIds));
            allowed = builder.or(allowed, workspaceFilter);
        }
        return allowed.build();
    }

    private FilterExpressionBuilder.Op workspaceEqualsAny(FilterExpressionBuilder builder, Set<String> readableWorkspaceIds) {
        if (readableWorkspaceIds.size() == 1) {
            return builder.eq("workspaceId", readableWorkspaceIds.iterator().next());
        }
        return builder.in("workspaceId", new ArrayList<>(readableWorkspaceIds));
    }

    private Document toSpringAiDocument(SourceDocument sourceDocument) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("id", sourceDocument.id());
        metadata.put("documentId", sourceDocument.documentId());
        metadata.put("fileName", sourceDocument.fileName());
        metadata.put("contentHash", sourceDocument.contentHash());
        metadata.put("title", nullToEmpty(sourceDocument.title()));
        metadata.put("source", sourceDocument.source());
        metadata.put("path", sourceDocument.path());
        metadata.put("chunkIndex", sourceDocument.chunkIndex());
        metadata.put("headingPath", nullToEmpty(sourceDocument.headingPath()));
        metadata.put("headingLevel", sourceDocument.headingLevel());
        metadata.put("startOffset", sourceDocument.startOffset());
        metadata.put("endOffset", sourceDocument.endOffset());
        metadata.put("chunkType", sourceDocument.chunkType());
        metadata.put("pageNumber", sourceDocument.pageNumber());
        metadata.put("category", sourceDocument.category());
        metadata.put("ownerUserId", sourceDocument.ownerUserId());
        metadata.put("workspaceId", sourceDocument.workspaceId());
        metadata.put("visibility", sourceDocument.visibility().name());

        return new Document(sourceDocument.id(), sourceDocument.content(), metadata);
    }

    private SourceDocument toSourceDocument(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String source = metadataValue(metadata, "source", "unknown");
        String path = metadataValue(metadata, "path", source);
        int chunkIndex = metadataInt(metadata, "chunkIndex");

        return new SourceDocument(
                metadataValue(metadata, "id", path + "#chunk-" + chunkIndex),
                document.getText(),
                metadataValue(metadata, "title", null),
                source,
                path,
                chunkIndex,
                metadataValue(metadata, "documentId", source),
                metadataValue(metadata, "fileName", source),
                metadataValue(metadata, "contentHash", ""),
                document.getScore() != null ? document.getScore() : metadataDouble(metadata, "distance"),
                metadataValue(metadata, "headingPath", null),
                metadataInt(metadata, "headingLevel"),
                metadataInt(metadata, "startOffset"),
                metadataInt(metadata, "endOffset"),
                metadataValue(metadata, "chunkType", "text-paragraph"),
                metadataValue(metadata, "category", "SOURCE"),
                metadataValue(metadata, "ownerUserId", ""),
                metadataValue(metadata, "workspaceId", ""),
                metadataVisibility(metadata),
                metadataInt(metadata, "pageNumber", 0)
        );
    }

    private SourceDocument toSourceDocument(String id, String content, Map<String, String> metadata) {
        Map<String, Object> values = new HashMap<>(metadata);
        values.putIfAbsent("id", id);
        return toSourceDocument(new Document(id, content == null ? "" : content, values));
    }

    private static <T> ObjectProvider<T> emptyProvider(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }

    private com.example.workbench.workspace.DocumentVisibility metadataVisibility(Map<String, Object> metadata) {
        String owner = metadataValue(metadata, "ownerUserId", "");
        String fallback = owner.isBlank() ? "PUBLIC" : "PRIVATE";
        try {
            return com.example.workbench.workspace.DocumentVisibility.valueOf(metadataValue(metadata, "visibility", fallback));
        } catch (IllegalArgumentException exception) {
            return com.example.workbench.workspace.DocumentVisibility.valueOf(fallback);
        }
    }

    private String metadataValue(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private int metadataInt(Map<String, Object> metadata, String key) {
        return metadataInt(metadata, key, -1);
    }

    private int metadataInt(Map<String, Object> metadata, String key, int defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value != null) {
            return Integer.parseInt(value.toString());
        }

        return defaultValue;
    }

    private double metadataDouble(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        if (value != null) {
            return Double.parseDouble(value.toString());
        }

        return 0.0;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
