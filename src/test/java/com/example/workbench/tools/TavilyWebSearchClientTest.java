package com.example.workbench.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TavilyWebSearchClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TavilyWebSearchClient client(String apiKey) {
        return new TavilyWebSearchClient(objectMapper, apiKey, "https://api.tavily.com", 8);
    }

    @Test
    void isConfiguredReturnsFalseWhenKeyBlank() {
        assertThat(client("").isConfigured()).isFalse();
        assertThat(client("   ").isConfigured()).isFalse();
    }

    @Test
    void isConfiguredReturnsTrueWhenKeyPresent() {
        assertThat(client("tvly-abc123").isConfigured()).isTrue();
    }

    @Test
    void searchReturnsEmptyWhenKeyMissing() {
        assertThat(client("").search("什么是知识图谱")).isEmpty();
    }

    @Test
    void searchReturnsEmptyWhenQueryBlank() {
        assertThat(client("tvly-abc123").search("   ")).isEmpty();
    }

    @Test
    void parseMapsResultsArrayToResults() {
        String json = """
                {
                  "query": "知识图谱",
                  "results": [
                    {
                      "title": "知识图谱入门",
                      "url": "https://example.com/kg",
                      "content": "知识图谱是结构化的语义知识库",
                      "score": 0.98
                    }
                  ]
                }
                """;

        var results = client("tvly-abc123").parse(json);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("知识图谱入门");
        assertThat(results.get(0).url()).isEqualTo("https://example.com/kg");
        assertThat(results.get(0).snippet()).isEqualTo("知识图谱是结构化的语义知识库");
    }

    @Test
    void parseSkipsEntriesWithoutUrl() {
        String json = """
                {
                  "results": [
                    { "title": "无 URL 条目", "content": "..." },
                    { "title": "正常条目", "url": "https://example.com/b", "content": "内容 B" }
                  ]
                }
                """;

        var results = client("tvly-abc123").parse(json);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).url()).isEqualTo("https://example.com/b");
    }

    @Test
    void parseReturnsEmptyOnMalformedJsonOrMissingResults() {
        assertThat(client("tvly-abc123").parse("not-a-json")).isEmpty();
        assertThat(client("tvly-abc123").parse("{}")).isEmpty();
        assertThat(client("tvly-abc123").parse("{\"results\":{}}")).isEmpty();
    }

    @Test
    void nameIsLowercaseTavily() {
        assertThat(client("tvly-abc123").name()).isEqualTo("tavily");
    }
}
