package com.example.workbench.tools;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WebSearchService {

    public List<WebSearchResult> search(String query) {
        if (query.contains("Spring AI") && query.contains("2.0") && query.toLowerCase().contains("mcp")) {
            return List.of(
                    new WebSearchResult(
                            "Spring AI MCP 2.0 新特性概览",
                            "https://docs.spring.io/spring-ai/reference/api/mcp/",
                            "Spring AI 2.0 的 MCP 方向通常关注更完整的 MCP client/server 集成、工具发现、资源访问、Prompt 能力和更清晰的自动配置。具体能力需要以官方发布说明为准。"
                    ),
                    new WebSearchResult(
                            "Spring AI MCP Client and Server",
                            "https://docs.spring.io/spring-ai/reference/",
                            "Spring AI 为 MCP 提供客户端和服务端集成，使应用可以通过标准协议连接外部工具、资源和上下文服务。"
                    )
            );
        }

        return List.of(new WebSearchResult(
                "搜索结果",
                "https://docs.spring.io/spring-ai/reference/",
                "未在本地知识库中找到足够信息。建议查看 Spring AI 官方参考文档和发布说明确认最新能力。"
        ));
    }
}
