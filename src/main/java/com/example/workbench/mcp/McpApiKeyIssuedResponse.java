package com.example.workbench.mcp;

/** 创建凭证结果：plaintext 只在本次响应出现一次，之后无法找回。 */
public record McpApiKeyIssuedResponse(
        McpApiKeyResponse key,
        String plaintext
) {
}
