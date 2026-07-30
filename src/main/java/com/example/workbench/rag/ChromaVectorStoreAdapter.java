package com.example.workbench.rag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class ChromaVectorStoreAdapter implements ScopedVectorStore {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorStoreAdapter.class);

    private final ObjectProvider<org.springframework.ai.vectorstore.VectorStore> chromaVectorStoreProvider;
    private final InMemoryVectorStore fallbackVectorStore;
    private List<String> currentDocumentIds = List.of();

    public ChromaVectorStoreAdapter(
            ObjectProvider<org.springframework.ai.vectorstore.VectorStore> chromaVectorStoreProvider,
            InMemoryVectorStore fallbackVectorStore
    ) {
        this.chromaVectorStoreProvider = chromaVectorStoreProvider;
        this.fallbackVectorStore = fallbackVectorStore;
    }

    public boolean isChromaConfigured() {
        return chromaVectorStoreProvider.getIfAvailable() != null;
    }

    @Override
    public void clear() {
        fallbackVectorStore.clear();
        org.springframework.ai.vectorstore.VectorStore chromaVectorStore = chromaVectorStoreProvider.getIfAvailable();
        if (chromaVectorStore == null) {
            currentDocumentIds = List.of();
            return;
        }

        try {
            // 全量重建不能依赖当前进程记住的 ID；按写入时必备的 id metadata 清除集合内全部项目向量。
            chromaVectorStore.delete(new FilterExpressionBuilder().ne("id", "").build());
            currentDocumentIds = List.of();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to clear all documents from Chroma", exception);
        }
    }

    @Override
    public void deleteByIds(List<String> ids) {
        fallbackVectorStore.deleteByIds(ids);

        org.springframework.ai.vectorstore.VectorStore chromaVectorStore = chromaVectorStoreProvider.getIfAvailable();
        if (chromaVectorStore == null || ids.isEmpty()) {
            return;
        }

        try {
            chromaVectorStore.delete(ids);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to delete documents from Chroma", exception);
        }
    }

    @Override
    public void addAll(List<SourceDocument> documents) {
        fallbackVectorStore.addAll(documents);
        currentDocumentIds = java.util.stream.Stream.concat(
                        currentDocumentIds.stream(),
                        documents.stream().map(SourceDocument::id)
                )
                .distinct()
                .toList();

        org.springframework.ai.vectorstore.VectorStore chromaVectorStore = chromaVectorStoreProvider.getIfAvailable();
        if (chromaVectorStore == null) {
            log.warn("Spring AI Chroma VectorStore is not available, using in-memory vector store only");
            return;
        }

        try {
            chromaVectorStore.add(documents.stream()
                    .map(this::toSpringAiDocument)
                    .toList());
        } catch (RuntimeException exception) {
            log.warn("Failed to write documents to Chroma, using in-memory vector store fallback errorType={}", exception.getClass().getSimpleName());
        }
    }

    @Override
    public void replaceAll(List<SourceDocument> documents) {
        clear();
        addAll(documents);
    }

    @Override
    public List<SourceDocument> similaritySearch(String query, int topK) {
        return similaritySearch(query, topK, null, null, false);
    }

    @Override
    public List<SourceDocument> similaritySearch(String query, int topK, String ownerUserId, String workspaceId) {
        return similaritySearch(query, topK, ownerUserId, workspaceId, true);
    }

    private List<SourceDocument> similaritySearch(String query, int topK, String ownerUserId, String workspaceId,
                                                  boolean scoped) {
        org.springframework.ai.vectorstore.VectorStore chromaVectorStore = chromaVectorStoreProvider.getIfAvailable();
        if (chromaVectorStore == null) {
            return fallbackSearch(query, topK, ownerUserId, workspaceId, scoped);
        }

        try {
            SearchRequest.Builder request = SearchRequest.builder()
                    .query(query)
                    .topK(topK);
            if (scoped) {
                request.filterExpression(visibilityFilter(ownerUserId, workspaceId));
            }
            List<Document> documents = chromaVectorStore.similaritySearch(request.build());

            if (documents == null || documents.isEmpty()) {
                return fallbackSearch(query, topK, ownerUserId, workspaceId, scoped);
            }

            return documents.stream()
                    .map(this::toSourceDocument)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("Failed to search Chroma, using in-memory vector store fallback errorType={}", exception.getClass().getSimpleName());
            return fallbackSearch(query, topK, ownerUserId, workspaceId, scoped);
        }
    }

    private List<SourceDocument> fallbackSearch(String query, int topK, String ownerUserId, String workspaceId,
                                                boolean scoped) {
        return scoped
                ? fallbackVectorStore.similaritySearch(query, topK, ownerUserId, workspaceId)
                : fallbackVectorStore.similaritySearch(query, topK);
    }

    private Filter.Expression visibilityFilter(String ownerUserId, String workspaceId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op allowed = builder.eq("visibility", "PUBLIC");

        if (ownerUserId != null && !ownerUserId.isBlank()) {
            FilterExpressionBuilder.Op privateFilter = builder.and(
                    builder.eq("visibility", "PRIVATE"),
                    builder.eq("ownerUserId", ownerUserId));
            if (workspaceId != null && !workspaceId.isBlank()) {
                privateFilter = builder.and(privateFilter, builder.eq("workspaceId", workspaceId));
            }
            allowed = builder.or(allowed, privateFilter);
        }

        if (workspaceId != null && !workspaceId.isBlank()) {
            FilterExpressionBuilder.Op workspaceFilter = builder.and(
                    builder.eq("visibility", "WORKSPACE"),
                    builder.eq("workspaceId", workspaceId));
            allowed = builder.or(allowed, workspaceFilter);
        }
        return allowed.build();
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
                metadataDouble(metadata, "distance"),
                metadataValue(metadata, "headingPath", null),
                metadataInt(metadata, "headingLevel"),
                metadataInt(metadata, "startOffset"),
                metadataInt(metadata, "endOffset"),
                metadataValue(metadata, "chunkType", "text-paragraph"),
                metadataValue(metadata, "category", "SOURCE"),
                metadataValue(metadata, "ownerUserId", ""),
                metadataValue(metadata, "workspaceId", ""),
                metadataVisibility(metadata)
        );
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
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value != null) {
            return Integer.parseInt(value.toString());
        }

        return -1;
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
