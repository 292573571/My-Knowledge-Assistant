package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

class RagControllerSourceFileTest {

    @Test
    void activeContentIsNeverInlineSafe() {
        // 回归锁：这些类型若以原 MIME 内联返回，脚本会在应用同源下执行（存储型 XSS）。
        assertThat(RagController.isInlineSafeSource(MediaType.TEXT_HTML)).isFalse();
        assertThat(RagController.isInlineSafeSource(MediaType.valueOf("image/svg+xml"))).isFalse();
        assertThat(RagController.isInlineSafeSource(MediaType.APPLICATION_XML)).isFalse();
        assertThat(RagController.isInlineSafeSource(MediaType.valueOf("text/xml"))).isFalse();
        assertThat(RagController.isInlineSafeSource(MediaType.APPLICATION_JSON)).isFalse();
        assertThat(RagController.isInlineSafeSource(MediaType.APPLICATION_OCTET_STREAM)).isFalse();
    }

    @Test
    void previewableTypesStayInlineSafe() {
        assertThat(RagController.isInlineSafeSource(MediaType.APPLICATION_PDF)).isTrue();
        assertThat(RagController.isInlineSafeSource(MediaType.IMAGE_PNG)).isTrue();
        assertThat(RagController.isInlineSafeSource(MediaType.IMAGE_JPEG)).isTrue();
        assertThat(RagController.isInlineSafeSource(MediaType.IMAGE_GIF)).isTrue();
        assertThat(RagController.isInlineSafeSource(MediaType.TEXT_PLAIN)).isTrue();
    }

    @Test
    void detectsMediaTypeFromDangerousFileNames() {
        assertThat(RagController.isInlineSafeSource(
                MediaTypeFactory.getMediaType("evil.html").orElse(MediaType.APPLICATION_OCTET_STREAM))).isFalse();
        assertThat(RagController.isInlineSafeSource(
                MediaTypeFactory.getMediaType("diagram.svg").orElse(MediaType.APPLICATION_OCTET_STREAM))).isFalse();
        assertThat(RagController.isInlineSafeSource(
                MediaTypeFactory.getMediaType("guide.pdf").orElse(MediaType.APPLICATION_OCTET_STREAM))).isTrue();
    }
}
