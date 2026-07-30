package com.example.workbench.rag;

import java.util.List;

public interface VectorStore {

    void clear();

    void deleteByIds(List<String> ids);

    void addAll(List<SourceDocument> documents);

    void replaceAll(List<SourceDocument> documents);

    List<SourceDocument> similaritySearch(String query, int topK);

}
