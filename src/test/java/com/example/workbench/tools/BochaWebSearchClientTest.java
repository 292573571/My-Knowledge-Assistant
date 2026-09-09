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
        String json = """
                {
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
                  "webPages": {
                    "value": [
                      { "name": "标题", "url": "https://example.com/a", "snippet": "只有片段" }
                    ]
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
                  "webPages": {
                    "value": [
                      { "name": "无 URL 的条目" },
                      { "name": "正常条目", "url": "https://example.com/b", "summary": "摘要 B" }
                    ]
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
        assertThat(client("sk-abc123").parse("{}")).isEmpty();
    }
}
