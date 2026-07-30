package com.example.workbench.rag;

import java.util.List;

public interface ScopedVectorStore extends VectorStore {

    List<SourceDocument> similaritySearch(String query, int topK, String ownerUserId, String workspaceId);
}
