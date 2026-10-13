package com.example.workbench.wiki;

/**
 * 创建 / 更新 wiki 页面的请求体。
 */
public record WikiPageRequest(
        String title,
        String slug,
        String parentId,
        String contentMd
) {
}
