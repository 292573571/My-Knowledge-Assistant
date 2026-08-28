package com.example.workbench.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

/**
 * 解析 PowerPoint 演示文稿(.pptx / .ppt),按「幻灯片序号 → 文本框文字」顺序提取可索引文本。
 * 复用 TextParagraphChunker,因此输出 documentType 为 text、块类型为 text-paragraph。
 * .pptx 与 .ppt 同属 poi-ooxml 依赖,无需引入新库。
 */
@Component
public class PowerPointDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pptx") || lower.endsWith(".ppt");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        if (isZip(content)) {
            try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(content))) {
                return parseModern(presentation);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalArgumentException("PowerPoint 文件已损坏或无法解析", exception);
            }
        }
        try (HSLFSlideShow presentation = new HSLFSlideShow(new ByteArrayInputStream(content))) {
            return parseLegacy(presentation);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("PowerPoint 文件已损坏或无法解析", exception);
        }
    }

    private ParsedDocument parseModern(XMLSlideShow presentation) {
        StringBuilder fullText = new StringBuilder();
        List<DocumentBlock> blocks = new ArrayList<>();
        List<org.apache.poi.xslf.usermodel.XSLFSlide> slides = presentation.getSlides();
        for (int index = 0; index < slides.size(); index++) {
            org.apache.poi.xslf.usermodel.XSLFSlide slide = slides.get(index);
            addBlock(fullText, blocks, "幻灯片 " + (index + 1));
            for (XSLFShape shape : slide.getShapes()) {
                if (shape instanceof XSLFTextShape textShape) {
                    String text = textShape.getText() == null ? "" : textShape.getText().strip();
                    if (!text.isBlank()) {
                        addBlock(fullText, blocks, text);
                    }
                }
            }
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("PowerPoint 未提取到可索引文本");
        }
        return new ParsedDocument("text", fullText.toString(), null, blocks);
    }

    private ParsedDocument parseLegacy(HSLFSlideShow presentation) {
        StringBuilder fullText = new StringBuilder();
        List<DocumentBlock> blocks = new ArrayList<>();
        List<HSLFSlide> slides = presentation.getSlides();
        for (int index = 0; index < slides.size(); index++) {
            HSLFSlide slide = slides.get(index);
            addBlock(fullText, blocks, "幻灯片 " + (index + 1));
            for (List<HSLFTextParagraph> paragraphGroup : slide.getTextParagraphs()) {
                String text = HSLFTextParagraph.getText(paragraphGroup);
                String trimmed = text == null ? "" : text.strip();
                if (!trimmed.isBlank()) {
                    addBlock(fullText, blocks, trimmed);
                }
            }
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("PowerPoint 未提取到可索引文本");
        }
        return new ParsedDocument("text", fullText.toString(), null, blocks);
    }

    private void addBlock(StringBuilder fullText, List<DocumentBlock> blocks, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!fullText.isEmpty()) {
            fullText.append("\n\n");
        }
        int start = fullText.length();
        fullText.append(content);
        blocks.add(new DocumentBlock(content, "text-paragraph", "", 0, start, fullText.length()));
    }

    private boolean isZip(byte[] content) {
        return content.length > 4
                && content[0] == (byte) 0x50 && content[1] == (byte) 0x4B
                && content[2] == (byte) 0x03 && content[3] == (byte) 0x04;
    }
}
