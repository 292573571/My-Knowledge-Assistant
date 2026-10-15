package com.example.workbench.wiki;

/**
 * 文件导入 wiki 的预处理结果：解析出的原始文件名、建议标题与 Markdown 正文，
 * 供前端填入 wiki 编辑器，保存时再作为一张 WIKI_PAGE 入库（既可检索，又能被
 * [[链接]] 与知识图谱串联）。
 */
public record WikiImportContent(
        String fileName,
        String title,
        String contentMd
) {
}
