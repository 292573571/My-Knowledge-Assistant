package com.example.workbench.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import com.example.workbench.workspace.DocumentVisibility;
import org.springframework.stereotype.Component;

@Component
public class InMemoryVectorStore implements ScopedVectorStore {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-zA-Z0-9]+");

    private final List<VectorDocument> vectorDocuments = new ArrayList<>();

    @Override
    public void clear() {
        vectorDocuments.clear();
    }

    @Override
    public void deleteByIds(List<String> ids) {
        vectorDocuments.removeIf(vectorDocument -> ids.contains(vectorDocument.document().id()));
    }

    @Override
    public void addAll(List<SourceDocument> documents) {
        deleteByIds(documents.stream().map(SourceDocument::id).toList());
        documents.stream()
                .map(document -> new VectorDocument(document, embed(document.content())))
                .forEach(vectorDocuments::add);
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
        Map<String, Double> queryVector = embed(query);

        return vectorDocuments.stream()
                .filter(vectorDocument -> !scoped || isVisible(vectorDocument.document(), ownerUserId, workspaceId))
                .map(vectorDocument -> new SearchResult(
                        vectorDocument.document(),
                        cosineSimilarity(queryVector, vectorDocument.vector())
                ))
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(topK)
                .map(result -> result.document().withScore(result.score()))
                .toList();
    }

    private boolean isVisible(SourceDocument document, String ownerUserId, String workspaceId) {
        if (document.visibility() == DocumentVisibility.PUBLIC) {
            return true;
        }
        if (document.visibility() == DocumentVisibility.PRIVATE) {
            return ownerUserId != null && !ownerUserId.isBlank()
                    && ownerUserId.equals(document.ownerUserId())
                    && (workspaceId == null || workspaceId.isBlank() || workspaceId.equals(document.workspaceId()));
        }
        return workspaceId != null && !workspaceId.isBlank() && workspaceId.equals(document.workspaceId());
    }

    private Map<String, Double> embed(String text) {
        Map<String, Double> vector = new HashMap<>();
        var matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));

        while (matcher.find()) {
            String token = matcher.group();
            vector.merge(token, 1.0, Double::sum);
        }

        return vector;
    }

    private double cosineSimilarity(Map<String, Double> left, Map<String, Double> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }

        double dotProduct = 0;
        double leftNorm = 0;
        double rightNorm = 0;

        for (double value : left.values()) {
            leftNorm += value * value;
        }

        for (double value : right.values()) {
            rightNorm += value * value;
        }

        for (Map.Entry<String, Double> entry : left.entrySet()) {
            dotProduct += entry.getValue() * right.getOrDefault(entry.getKey(), 0.0);
        }

        return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record VectorDocument(
            SourceDocument document,
            Map<String, Double> vector
    ) {
    }

    private record SearchResult(
            SourceDocument document,
            double score
    ) {
    }
}
