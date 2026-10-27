package com.example.workbench.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 联网搜索门面：按 {@code workbench.rag.web-search.provider-order}（默认 tavily,bocha）
 * 依次尝试已配置的源，主源返回空或异常时自动切换到备源。
 *
 * <p>「空结果」即视为该源不可用（配额耗尽、解析失败等情况客户端都已归一为空列表）。
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private final List<WebSearchClient> orderedClients;

    public WebSearchService(
            List<WebSearchClient> clients,
            @Value("${workbench.rag.web-search.provider-order:tavily,bocha}") String providerOrder) {
        this.orderedClients = orderClients(clients, providerOrder);
    }

    public List<WebSearchResult> search(String query) {
        String effectiveQuery = query == null ? "" : query.strip();
        if (effectiveQuery.isBlank()) {
            return List.of();
        }
        String lastProvider = null;
        for (WebSearchClient client : orderedClients) {
            if (!client.isConfigured()) {
                log.debug("Web search provider {} skipped: not configured", client.name());
                continue;
            }
            List<WebSearchResult> results = client.search(effectiveQuery);
            if (!results.isEmpty()) {
                if (lastProvider != null) {
                    log.info("Web search fallback to {} after {} returned empty", client.name(), lastProvider);
                }
                return results;
            }
            lastProvider = client.name();
        }
        if (lastProvider != null) {
            log.warn("Web search: all configured providers returned empty (last={})", lastProvider);
        }
        return List.of();
    }

    /** 供测试与诊断观察当前源顺序（仅已配置判断前的静态顺序）。 */
    List<String> providerOrder() {
        return orderedClients.stream().map(WebSearchClient::name).toList();
    }

    private static List<WebSearchClient> orderClients(List<WebSearchClient> clients, String providerOrder) {
        Map<String, WebSearchClient> byName = new LinkedHashMap<>();
        for (WebSearchClient client : clients) {
            byName.putIfAbsent(client.name(), client);
        }
        List<WebSearchClient> ordered = new ArrayList<>();
        if (providerOrder != null && !providerOrder.isBlank()) {
            for (String token : providerOrder.split(",")) {
                String name = token.strip().toLowerCase();
                WebSearchClient client = byName.remove(name);
                if (client != null) {
                    ordered.add(client);
                }
            }
        }
        ordered.addAll(byName.values());
        return List.copyOf(ordered);
    }
}
