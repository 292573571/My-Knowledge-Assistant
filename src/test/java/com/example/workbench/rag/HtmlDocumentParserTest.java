package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HtmlDocumentParserTest {

    private final HtmlDocumentParser parser = new HtmlDocumentParser();

    @Test
    void cleansDomAndPreservesStructuralBlockOrder() {
        String html = """
                <!doctype html><html><head><title>HTML Guide</title><style>.hidden{}</style></head><body>
                <script>alert('xss')</script>
                <h1>Architecture</h1>
                <p>Intro with <code>parse()</code>.</p>
                <ul><li>Parent<ul><li>Child</li></ul></li></ul>
                <pre><code>  if (ready) {
                    run();
                  }
                </code></pre>
                <table><thead><tr><th>Name</th><th>Value</th></tr></thead>
                <tbody><tr><td>Parser</td><td>DOM | order</td></tr></tbody></table>
                <h2>Storage</h2><p>Final text</p>
                </body></html>
                """;

        ParsedDocument document = parser.parse(html);

        assertThat(document.title()).isEqualTo("HTML Guide");
        assertThat(document.blocks()).extracting(DocumentBlock::blockType).containsExactly(
                "html-heading", "html-paragraph", "html-list-item", "html-list-item",
                "html-code", "html-table", "html-heading", "html-paragraph"
        );
        assertThat(document.blocks()).extracting(DocumentBlock::headingPath).containsExactly(
                "Architecture", "Architecture", "Architecture", "Architecture",
                "Architecture", "Architecture", "Architecture > Storage", "Architecture > Storage"
        );
        assertThat(document.blocks().get(2).content()).isEqualTo("- Parent");
        assertThat(document.blocks().get(3).content()).isEqualTo("  - Child");
        assertThat(document.blocks().get(4).content()).startsWith("  if (ready)").contains("    run();");
        assertThat(document.blocks().get(5).content()).contains("| Name | Value |", "DOM \\| order");
        assertThat(document.content()).doesNotContain("alert('xss')", ".hidden{}");
        assertThat(document.blocks()).allSatisfy(block ->
                assertThat(document.content().substring(block.startOffset(), block.endOffset()))
                        .isEqualTo(block.content())
        );
    }

    @Test
    void usesFirstH1AsTitleAndKeepsContentOutsideMain() {
        ParsedDocument document = parser.parse("""
                <body><header>Version notice</header><main><h1>Guide</h1><p>Main content</p></main><footer>License</footer></body>
                """);

        assertThat(document.title()).isEqualTo("Guide");
        assertThat(document.content()).contains("Version notice", "Main content", "License");
    }

    @Test
    void removesNavigationAndSidebarNoise() {
        ParsedDocument document = parser.parse("""
                <body><nav>首页 产品中心 联系我们</nav><aside>热门推荐 广告入口</aside>
                <main><h1>运维手册</h1><p>订单服务的恢复时间目标是十五分钟。</p></main></body>
                """);

        assertThat(document.content()).contains("运维手册", "恢复时间目标")
                .doesNotContain("首页", "产品中心", "热门推荐", "广告入口");
    }

    @Test
    void rejectsNonUtf8DeclarationAndInvalidUtf8Bytes() {
        assertThatThrownBy(() -> parser.parse("<meta charset='GBK'><p>text</p>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("charset 声明必须为 UTF-8");
        assertThatThrownBy(() -> parser.parse(new byte[]{(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须使用 UTF-8 编码");
    }

    @Test
    void rejectsHtmlWithoutIndexableContent() {
        assertThatThrownBy(() -> parser.parse("<script>onlyNoise()</script>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未提取到可索引文本");
    }
}
