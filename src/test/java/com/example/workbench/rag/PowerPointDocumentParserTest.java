package com.example.workbench.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

class PowerPointDocumentParserTest {

    private static final OcrEngine NO_OCR = image -> {
        throw new AssertionError("该用例不应触发 OCR");
    };

    @Test
    void autoModeSkipsOcrWhenNativeTextIsEnough() throws Exception {
        PowerPointDocumentParser parser = parser(NO_OCR, "auto");
        byte[] bytes = deck(slide -> textBox(slide, "这是一段足够长的幻灯片正文内容"));

        ParsedDocument parsed = parser.parse(bytes);

        assertTrue(parsed.content().contains("这是一段足够长的幻灯片正文内容"));
        assertEquals(PowerPointDocumentParser.DOCUMENT_TYPE, parsed.documentType());
    }

    @Test
    void ocrsPureImageSlideAndKeepsSlideNumber() throws Exception {
        PowerPointDocumentParser parser = parser(image -> "第三季度营收同比增长 18%", "auto");
        byte[] bytes = deck(PowerPointDocumentParserTest::pictureOnly);

        ParsedDocument parsed = parser.parse(bytes);

        assertTrue(parsed.content().contains("第三季度营收同比增长 18%"));
        List<DocumentBlock> blocks = parsed.blocks();
        assertEquals(1, blocks.size());
        assertEquals(PowerPointDocumentParser.SLIDE_BLOCK_TYPE, blocks.get(0).blockType());
        assertEquals(1, blocks.get(0).pageNumber());
    }

    @Test
    void mergesNativeAndOcrTextWithoutDuplicates() throws Exception {
        PowerPointDocumentParser parser = parser(image -> "季度复盘\n补充说明：毛利率提升 3 个点", "auto");
        byte[] bytes = deck(slide -> {
            textBox(slide, "季度复盘");
            pictureOnly(slide);
        });

        ParsedDocument parsed = parser.parse(bytes);

        assertEquals(1, occurrences(parsed.content(), "季度复盘"));
        assertTrue(parsed.content().contains("补充说明：毛利率提升 3 个点"));
    }

    @Test
    void alwaysModeOcrsEvenTextRichSlides() throws Exception {
        PowerPointDocumentParser parser = parser(image -> "图片里的补充信息", "always");
        byte[] bytes = deck(slide -> textBox(slide, "这是一段足够长的幻灯片正文内容"));

        ParsedDocument parsed = parser.parse(bytes);

        assertTrue(parsed.content().contains("这是一段足够长的幻灯片正文内容"));
        assertTrue(parsed.content().contains("图片里的补充信息"));
    }

    @Test
    void failsWhenDeckOnlyContainsSlideNumbers() throws Exception {
        PowerPointDocumentParser parser = parser(image -> "", "auto");
        byte[] bytes = deck(PowerPointDocumentParserTest::pictureOnly);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes));

        assertTrue(error.getMessage().contains("未提取到可索引正文"));
    }

    @Test
    void chunkerKeepsSlidePageNumbers() throws Exception {
        PowerPointDocumentParser parser = parser(NO_OCR, "auto");
        byte[] bytes = deck(
                slide -> textBox(slide, "第一页正文内容足够长可以跳过 OCR 识别"),
                slide -> textBox(slide, "第二页正文内容同样足够长可以跳过识别")
        );

        List<DocumentChunk> chunks = new PptSlideChunker().chunk(parser.parse(bytes));

        assertFalse(chunks.isEmpty());
        assertEquals(1, chunks.get(0).pageNumber());
        assertEquals(2, chunks.get(chunks.size() - 1).pageNumber());
        assertTrue(chunks.stream().allMatch(chunk -> PowerPointDocumentParser.SLIDE_BLOCK_TYPE.equals(chunk.chunkType())));
    }

    private PowerPointDocumentParser parser(OcrEngine engine, String mode) {
        return new PowerPointDocumentParser(engine, mode, 800, 500, 200);
    }

    private byte[] deck(java.util.function.Consumer<XSLFSlide>... decorators) throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow()) {
            for (java.util.function.Consumer<XSLFSlide> decorator : decorators) {
                decorator.accept(presentation.createSlide());
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static void textBox(XSLFSlide slide, String text) {
        XSLFTextBox textBox = slide.createTextBox();
        textBox.setText(text);
    }

    /** 放一张真实图片，模拟只有图片、没有原生文本的幻灯片。 */
    private static void pictureOnly(XSLFSlide slide) {
        try {
            BufferedImage image = new BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 240, 120);
            graphics.setColor(Color.DARK_GRAY);
            graphics.fillRect(20, 20, 200, 80);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            XSLFPictureData data = slide.getSlideShow().addPicture(output.toByteArray(), PictureData.PictureType.PNG);
            slide.createPicture(data);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int occurrences(String text, String target) {
        return text.split(java.util.regex.Pattern.quote(target), -1).length - 1;
    }
}
