package com.example.workbench.tools;

import com.example.workbench.rag.SourceDocument;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SearchTools {

    public List<SourceDocument> keywordSearch(List<SourceDocument> documents, String keyword) {
        String normalizedKeyword = keyword.toLowerCase();

        return documents.stream()
                .filter(document -> containsIgnoreCase(document.title(), normalizedKeyword)
                        || containsIgnoreCase(document.content(), normalizedKeyword))
                .toList();
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase().contains(normalizedKeyword);
    }
}
