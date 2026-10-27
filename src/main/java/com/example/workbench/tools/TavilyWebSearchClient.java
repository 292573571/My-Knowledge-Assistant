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

/**
 * Tavily 搜索客户端（主源）。
 *
 * <p>API：{@code POST {base-url}/search}，Bearer 鉴权，响应
 * {@code {"results":[{"title","url","content"}]}}。免费额度 1000 次/月。
 */
@Component
public class TavilyWebSearchClient implements WebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(TavilyWebSearchClient.class);

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final int count;
    private final HttpClient httpClient;

    public TavilyWebSearchClient(
            ObjectMapper objectMapper,
            @Value("${workbench.rag.web-search.tavily.api-key:}") String apiKey,
            @Value("${workbench.rag.web-search.tavily.base-url:https://api.tavily.com}") String baseUrl,
            @Value("${workbench.rag.web-search.tavily.count:8}") int count) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.tavily.com" : baseUrl.strip();
        this.count = Math.max(1, Math.min(count, 20));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public List<WebSearchResult> search(String query) {
        if (!isConfigured()) {
            log.debug("Tavily web search skipped: api key not configured");
            return List.of();
        }
        String effectiveQuery = query == null ? "" : query.strip();
        if (effectiveQuery.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", effectiveQuery);
            payload.put("max_results", count);
            payload.put("include_answer", false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/search"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Tavily web search failed status={} body={}", response.statusCode(), truncate(response.body()));
                return List.of();
            }
            return parse(response.body());
        } catch (Exception exception) {
            log.warn("Tavily web search error errorType={} message={}", exception.getClass().getSimpleName(), exception.getMessage());
            return List.of();
        }
    }

    List<WebSearchResult> parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                log.debug("Tavily web search parse: results missing or not array, rootKeys={}", collectTopLevelKeys(root));
                return List.of();
            }
            List<WebSearchResult> parsed = new ArrayList<>();
            for (JsonNode item : results) {
                String title = text(item, "title");
                String url = text(item, "url");
                String snippet = text(item, "content");
                if (url.isBlank()) {
                    continue;
                }
                parsed.add(new WebSearchResult(title, url, snippet));
            }
            return parsed;
        } catch (Exception exception) {
            log.warn("Tavily web search parse error errorType={}", exception.getClass().getSimpleName());
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

    private static java.util.Set<String> collectTopLevelKeys(JsonNode node) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
