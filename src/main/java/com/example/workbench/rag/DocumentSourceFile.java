package com.example.workbench.rag;

/**
 * 经过权限和路径校验后读取的文档源文件。
 *
 * @param fileName 用户上传时的原始文件名
 * @param content 文件二进制内容
 */
public record DocumentSourceFile(String fileName, byte[] content) {
}
