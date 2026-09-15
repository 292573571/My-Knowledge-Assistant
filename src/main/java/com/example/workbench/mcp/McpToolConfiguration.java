package com.example.workbench.mcp;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只把知识库只读工具注册给 MCP Server。
 *
 * <p>显式声明 ToolCallbackProvider 而不是依赖自动扫描，是为了避免把教学/运维 Agent 的
 * 内部工具（TeachingAgentTools、MaintenanceAgentTools）一并暴露给外部 MCP 客户端。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class McpToolConfiguration {

    @Bean
    public MethodToolCallbackProvider knowledgeMcpToolCallbackProvider(KnowledgeMcpTools knowledgeMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeMcpTools)
                .build();
    }
}
