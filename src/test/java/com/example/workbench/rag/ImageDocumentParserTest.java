package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ImageDocumentParserTest {

    @Test
    void parsesPngAndJpegUsingOcr() throws Exception {
        ImageDocumentParser parser = new ImageDocumentParser(image -> "图片中的 SSL 配置说明");

        assertThat(parser.parse(ImageTestDocuments.png()).content()).contains("SSL 配置");
        assertThat(parser.parse(ImageTestDocuments.jpeg()).documentType()).isEqualTo("image");
    }

    @Test
    void rejectsInvalidImageSignature() {
        ImageDocumentParser parser = new ImageDocumentParser(image -> "text");

        assertThatThrownBy(() -> parser.parse("not an image".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有效的 PNG 或 JPEG");
    }

    @Test
    void rejectsImageWithoutRecognizedText() throws Exception {
        ImageDocumentParser parser = new ImageDocumentParser(image -> " ");

        assertThatThrownBy(() -> parser.parse(ImageTestDocuments.png()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未识别到可索引文字");
    }
}
