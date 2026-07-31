package com.example.workbench.rag;

import java.nio.charset.StandardCharsets;

/**
 * 将一种受支持的源文件解析为统一的文档结构，不负责路径、权限和持久化。
 */
public interface DocumentParser {

    boolean supports(String fileName);

    ParsedDocument parse(byte[] content);

    /**
     * 为文本解析测试和内部调用提供 UTF-8 便利入口。
     */
    default ParsedDocument parse(String content) {
        return parse(content.getBytes(StandardCharsets.UTF_8));
    }
}
