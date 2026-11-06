package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyWordDocumentParserTest {

    private static final char FIELD_BEGIN = '\u0013';
    private static final char FIELD_SEPARATOR = '\u0014';
    private static final char FIELD_END = '\u0015';

    @Test
    void dropsTableOfContentsEntryBuiltFromFieldCodes() {
        String raw = "\u0013 HYPERLINK \\l _Toc7770 \u00143.1 表类型维护\t\u0013 PAGEREF _Toc7770 \\h \u00144\u0015\u0015";

        assertThat(LegacyWordDocumentParser.cleanParagraph(raw)).isEmpty();
    }

    @Test
    void dropsTableOfContentsDefinitionParagraph() {
        String raw = "目录\u0013TOC \\o \"1-3\" \\h \\u \u0014\u0013 HYPERLINK \\l _Toc8148 \u00141 简介\u0015";

        assertThat(LegacyWordDocumentParser.cleanParagraph(raw)).isEmpty();
    }

    @Test
    void keepsVisibleFieldResultAndRemovesInstruction() {
        String raw = "参见" + FIELD_BEGIN + " REF _Ref123 \\h " + FIELD_SEPARATOR + "第 3 章"
                + FIELD_END + "的内容。";

        assertThat(LegacyWordDocumentParser.cleanParagraph(raw)).isEqualTo("参见第 3 章的内容。");
    }

    @Test
    void keepsPlainBodyParagraph() {
        String raw = "3.1 表类型维护：用于配置源端与目标端的表类型映射关系。";

        assertThat(LegacyWordDocumentParser.cleanParagraph(raw))
                .isEqualTo("3.1 表类型维护：用于配置源端与目标端的表类型映射关系。");
    }

    @Test
    void removesEmbeddedObjectAndCellMarkers() {
        String raw = "配置步骤\u0001：填写连接信息\u0007";

        assertThat(LegacyWordDocumentParser.cleanParagraph(raw)).isEqualTo("配置步骤：填写连接信息");
    }
}
