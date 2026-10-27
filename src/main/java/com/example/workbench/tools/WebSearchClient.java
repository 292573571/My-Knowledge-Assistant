package com.example.workbench.tools;

import java.util.List;

/**
 * 联网搜索客户端的统一契约。
 *
 * <p>实现方必须做到「失败不抛异常」：任何网络错误、非 200 响应、解析失败一律返回空列表，
 * 由 {@link WebSearchService} 据此切换到下一个备源。
 */
public interface WebSearchClient {

    /** 供 {@code workbench.rag.web-search.provider-order} 排序用的小写标识。 */
    String name();

    boolean isConfigured();

    List<WebSearchResult> search(String query);
}
