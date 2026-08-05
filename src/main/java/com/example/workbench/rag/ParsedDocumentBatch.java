package com.example.workbench.rag;

/**
 * 文档解析批次及其页面范围。
 *
 * @param batchIndex 从 1 开始的批次序号
 * @param totalBatches 总批次数
 * @param totalPages 文档总页数
 * @param startPage 起始页码
 * @param endPage 结束页码
 * @param document 当前批次解析结果
 */
public record ParsedDocumentBatch(
        int batchIndex,
        int totalBatches,
        int totalPages,
        int startPage,
        int endPage,
        ParsedDocument document
) {
}
