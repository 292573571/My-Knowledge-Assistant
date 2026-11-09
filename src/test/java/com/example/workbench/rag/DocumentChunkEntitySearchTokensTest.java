package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.workspace.DocumentVisibility;
import org.junit.jupiter.api.Test;

/**
 * 入库侧契约：分块实体构造时必须生成 search_tokens，
 * 否则 V40 迁移后的 search_vector（simple 配置）对中文永远为空。
 */
class DocumentChunkEntitySearchTokensTest {

    @Test
    void constructorPopulatesSearchTokensFromSearchableText() {
        SourceDocument source = new SourceDocument("doc-1#chunk-0",
                "2 准备工作 2.1 生成jar包，方便在迁移平台导入表、字典以及枚举。", "准备工作",
                "泰山数据迁移平台操作手册.doc", "docs/taishan.doc", 0, "doc-1", "泰山数据迁移平台操作手册.doc",
                "hash-1", 0.0, "准备工作", 2, 0, 120, "text-paragraph", "SOURCE",
                "u1", "ws-1", DocumentVisibility.PUBLIC);

        DocumentChunkEntity entity = new DocumentChunkEntity(source);

        assertThat(entity.searchTokens()).isNotBlank();
        assertThat(entity.searchTokens()).contains("准备", "工作", "jar");
        assertThat(entity.searchTokens()).doesNotContain("\n");
    }

    @Test
    void searchableTextKeepsOriginalConcatenationForScoreFallback() {
        SourceDocument source = new SourceDocument("doc-1#chunk-0", "分块正文内容", "标题",
                "notes.md", "docs/notes.md", 0, "doc-1", "notes.md", "hash-1", 0.0,
                "标题", 1, 0, 10, "text-paragraph", "SOURCE", "u1", "ws-1", DocumentVisibility.WORKSPACE);

        DocumentChunkEntity entity = new DocumentChunkEntity(source);

        assertThat(entity.searchableText()).contains("notes.md", "标题", "分块正文内容");
    }
}
