package com.example.workbench.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BochaWebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(BochaWebSearchClient.class);

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final int count;
    private final HttpClient httpClient;

    public BochaWebSearchClient(
            ObjectMapper objectMapper,
            @Value("${workbench.rag.web-search.bocha.api-key:}") String apiKey,
            @Value("${workbench.rag.web-search.bocha.base-url:https://api.bochaai.com}") String baseUrl,
            @Value("${workbench.rag.web-search.bocha.count:8}") int count) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.bochaai.com" : baseUrl.strip();
        this.count = Math.max(1, Math.min(count, 50));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public List<WebSearchResult> search(String query) {
        if (!isConfigured()) {
            log.debug("Bocha web search skipped: api key not configured");
            return List.of();
        }
        String effectiveQuery = query == null ? "" : query.strip();
        if (effectiveQuery.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", effectiveQuery);
            payload.put("freshness", "noLimit");
            payload.put("summary", true);
            payload.put("count", count);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/web-search"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Bocha web search failed status={} body={}", response.statusCode(), truncate(response.body()));
                return List.of();
            }
            return parse(response.body());
        } catch (Exception exception) {
            log.warn("Bocha web search error errorType={} message={}", exception.getClass().getSimpleName(), exception.getMessage());
            return List.of();
        }
    }

    List<WebSearchResult> parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode value = root.path("webPages").path("value");
            if (!value.isArray()) {
                return List.of();
            }
            List<WebSearchResult> results = new ArrayList<>();
            for (JsonNode item : value) {
                String title = text(item, "name");
                String url = text(item, "url");
                String snippet = text(item, "summary");
                if (snippet.isBlank()) {
                    snippet = text(item, "snippet");
                }
                if (url.isBlank()) {
                    continue;
                }
                results.add(new WebSearchResult(title, url, snippet));
            }
            return results;
        } catch (Exception exception) {
            log.warn("Bocha web search parse error errorType={}", exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
