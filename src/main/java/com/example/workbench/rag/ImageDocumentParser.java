package com.example.workbench.rag;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * 校验 PNG/JPEG 图片并通过 OCR 提取可索引文本。
 */
@Component
public class ImageDocumentParser implements DocumentParser {

    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private final OcrEngine ocrEngine;

    /**
     * 创建图片 OCR 解析器。
     *
     * @param ocrEngine 图片 OCR 引擎
     */
    public ImageDocumentParser(OcrEngine ocrEngine) {
        this.ocrEngine = ocrEngine;
    }

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        if (!isPng(content) && !isJpeg(content)) {
            throw new IllegalArgumentException("文件不是有效的 PNG 或 JPEG 图片");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            BufferedImage image = readValidatedImage(input);
            String text = ocrEngine.recognize(image).strip();
            if (text.isBlank()) {
                throw new IllegalArgumentException("图片未识别到可索引文字");
            }
            DocumentBlock block = new DocumentBlock(text, "image-ocr", "", 0, 0, text.length(), 0);
            return new ParsedDocument("image", text, null, List.of(block));
        } catch (IOException exception) {
            throw new IllegalArgumentException("图片已损坏或无法解析", exception);
        }
    }

    private BufferedImage readValidatedImage(ImageInputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("图片已损坏或无法解析");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new IllegalArgumentException("图片已损坏或无法解析");
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if ((long) width * height > MAX_IMAGE_PIXELS) {
                throw new IllegalArgumentException("图片像素尺寸过大，不能超过 4000 万像素");
            }
            BufferedImage image = reader.read(0);
            if (image == null) {
                throw new IllegalArgumentException("图片已损坏或无法解析");
            }
            return image;
        } finally {
            reader.dispose();
        }
    }

    private boolean isPng(byte[] content) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (content.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) return false;
        }
        return true;
    }

    private boolean isJpeg(byte[] content) {
        return content.length >= 3 && content[0] == (byte) 0xFF
                && content[1] == (byte) 0xD8 && content[2] == (byte) 0xFF;
    }
}
