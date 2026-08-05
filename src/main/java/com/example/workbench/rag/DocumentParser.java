package com.example.workbench.rag;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * 将一种受支持的源文件解析为统一的文档结构，不负责路径、权限和持久化。
 */
public interface DocumentParser {

    boolean supports(String fileName);

    ParsedDocument parse(byte[] content);

    /**
     * 按批次解析文档。普通格式默认保持单批行为，PDF 解析器会覆盖此方法按页面拆分。
     *
     * @param content 原始文件内容
     * @param batchSize 单批页面数或格式相关的批次大小
     * @return 解析批次
     */
    default List<ParsedDocument> parseBatches(byte[] content, int batchSize) {
        List<ParsedDocument> batches = new ArrayList<>();
        parseEachBatch(content, batchSize, batch -> batches.add(batch.document()));
        return List.copyOf(batches);
    }

    /**
     * 逐批解析并立即交给调用方，避免强制在内存中保留全部解析结果。
     *
     * @param content 原始文件内容
     * @param batchSize 单批大小
     * @param consumer 批次消费者
     */
    default void parseEachBatch(byte[] content, int batchSize, Consumer<ParsedDocumentBatch> consumer) {
        parseEachBatch(content, batchSize, ignored -> true, consumer);
    }

    /**
     * 只解析调用方选中的批次，恢复任务可据此跳过已完成批次。
     *
     * @param content 原始文件内容
     * @param batchSize 单批大小
     * @param shouldParse 判断批次是否需要解析
     * @param consumer 批次消费者
     */
    default void parseEachBatch(byte[] content, int batchSize, IntPredicate shouldParse,
                                Consumer<ParsedDocumentBatch> consumer) {
        if (!shouldParse.test(1)) return;
        ParsedDocument document = parse(content);
        int startPage = document.blocks().stream().mapToInt(DocumentBlock::pageNumber).min().orElse(0);
        int endPage = document.blocks().stream().mapToInt(DocumentBlock::pageNumber).max().orElse(startPage);
        consumer.accept(new ParsedDocumentBatch(1, 1, Math.max(endPage, 1), startPage, endPage, document));
    }

    /**
     * 为文本解析测试和内部调用提供 UTF-8 便利入口。
     */
    default ParsedDocument parse(String content) {
        return parse(content.getBytes(StandardCharsets.UTF_8));
    }
}
