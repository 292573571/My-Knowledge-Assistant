package com.example.workbench.wiki;

/**
 * 创建 / 更新 wiki 页面的请求体。sourceType/sourceRef 为笔记来源溯源（阶段4 知识沉淀），
 * 允许的 sourceType：MANUAL / DOCUMENT / CHAT；空值视为历史数据或手写。
 */
public record WikiPageRequest(
        String title,
        String slug,
        String parentId,
        String contentMd,
        String sourceType,
        String sourceRef
) {
    public WikiPageRequest(String title, String slug, String parentId, String contentMd) {
        this(title, slug, parentId, contentMd, null, null);
    }
}
