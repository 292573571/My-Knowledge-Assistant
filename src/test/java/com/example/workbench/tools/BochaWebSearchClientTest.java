package com.example.workbench.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class BochaWebSearchClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BochaWebSearchClient client(String apiKey) {
        return new BochaWebSearchClient(objectMapper, apiKey, "https://api.bochaai.com", 8);
    }

    @Test
    void isConfiguredReturnsFalseWhenKeyBlank() {
        assertThat(client("").isConfigured()).isFalse();
        assertThat(client("   ").isConfigured()).isFalse();
    }

    @Test
    void isConfiguredReturnsTrueWhenKeyPresent() {
        assertThat(client("sk-abc123").isConfigured()).isTrue();
    }

    @Test
    void searchReturnsEmptyWhenKeyMissing() {
        assertThat(client("").search("kettle 是什么")).isEmpty();
    }

    @Test
    void searchReturnsEmptyWhenQueryBlank() {
        assertThat(client("sk-abc123").search("   ")).isEmpty();
    }

    @Test
    void parseMapsWebPagesToResultsPreferringSummary() {
        // 真实博查响应结构：results 嵌套在 data.webPages.value 下
        String json = """
                {
                  "code": 200,
                  "data": {
                    "webPages": {
                      "value": [
                        {
                          "name": "Kettle ETL 简介",
                          "url": "https://example.com/kettle",
                          "snippet": "原始片段",
                          "summary": "AI 生成的完整摘要"
                        }
                      ]
                    }
                  }
                }
                """;

        var results = client("sk-abc123").parse(json);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Kettle ETL 简介");
        assertThat(results.get(0).url()).isEqualTo("https://example.com/kettle");
        assertThat(results.get(0).snippet()).isEqualTo("AI 生成的完整摘要");
    }

    @Test
    void parseFallsBackToSnippetWhenSummaryMissing() {
        String json = """
                {
                  "code": 200,
                  "data": {
                    "webPages": {
                      "value": [
                        { "name": "标题", "url": "https://example.com/a", "snippet": "只有片段" }
                      ]
                    }
                  }
                }
                """;

        var results = client("sk-abc123").parse(json);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).snippet()).isEqualTo("只有片段");
    }

    @Test
    void parseSkipsEntriesWithoutUrl() {
        String json = """
                {
                  "code": 200,
                  "data": {
                    "webPages": {
                      "value": [
                        { "name": "无 URL 的条目" },
                        { "name": "正常条目", "url": "https://example.com/b", "summary": "摘要 B" }
                      ]
                    }
                  }
                }
                """;

        var results = client("sk-abc123").parse(json);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).url()).isEqualTo("https://example.com/b");
    }

    @Test
    void parseReturnsEmptyOnMalformedJson() {
        assertThat(client("sk-abc123").parse("not-a-json")).isEmpty();
    }

    @Test
    void parseReturnsEmptyWhenWebPagesMissing() {
        // 既无 data.webPages 也没有顶层 webPages 时返回空
        assertThat(client("sk-abc123").parse("{}")).isEmpty();
        assertThat(client("sk-abc123").parse("{\"data\":{}}")).isEmpty();
    }

    /**
     * 真实博查响应回归测试：用 curl 实际返回的 8 条热点新闻结构断言解析后能拿到所有结果。
     * 这是 bug "0 results despite 200 OK" 的回归锁——之前少了 data 包裹层导致空数组。
     */
    @Test
    void parseHandlesRealBochaResponseWithEightResults() {
        String json = """
                {
                  "code": 200,
                  "log_id": "6aa0f53f65461bf3",
                  "msg": null,
                  "data": {
                    "_type": "SearchResponse",
                    "queryContext": { "originalQuery": "今天的热点新闻" },
                    "webPages": {
                      "totalEstimatedMatches": 124027,
                      "value": [
                        {"name": "今日早报每日热点15条新闻简报", "url": "https://c.m.163.com/news/a/L6EPE3HI0534QBVQ.html", "summary": "9月10日 教师节..."},
                        {"name": "要闻", "url": "http://nnwb.nnnews.net/pc/column/202609/10/col2.html", "summary": "版面导读..."},
                        {"name": "时事·中国", "url": "http://nnwb.nnnews.net/pc/column/202609/10/col7.html", "summary": "版面导读..."},
                        {"name": "「早报」深夜,苹果折叠屏手机发布", "url": "https://c.m.163.com/news/a/L6ESLMK705198CJN.html", "summary": "宏观新闻..."},
                        {"name": "黑料不打烊", "url": "https://suizhouhszh.org.cn/star/20260908-75271426-CEQAAXJ.shtml", "summary": "近期娱乐圈热点..."},
                        {"name": "金融领域重磅发布会", "url": "https://finance.eastmoney.com/a/202609103869730726.html", "summary": "今日关注..."},
                        {"name": "今日导读", "url": "https://fjrb.fjdaily.com/pc/con/202609/10/content_563269.html", "summary": "失能不失护..."},
                        {"name": "日本韩国又要开始互骂了", "url": "https://user.guancha.cn/main/content?id=1731533", "summary": "今天的风闻社区..."}
                      ]
                    }
                  }
                }
                """;

        var results = client("sk-abc123").parse(json);

        assertThat(results).hasSize(8);
        assertThat(results.get(0).title()).isEqualTo("今日早报每日热点15条新闻简报");
        assertThat(results.get(7).url()).contains("user.guancha.cn");
    }
}
