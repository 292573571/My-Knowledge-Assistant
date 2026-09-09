package com.example.workbench.tools;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WebSearchService {

    private final BochaWebSearchClient bochaWebSearchClient;

    public WebSearchService(BochaWebSearchClient bochaWebSearchClient) {
        this.bochaWebSearchClient = bochaWebSearchClient;
    }

    public List<WebSearchResult> search(String query) {
        return bochaWebSearchClient.search(query);
    }
}
