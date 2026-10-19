package com.example.workbench.rag;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 解析 PowerPoint 演示文稿(.pptx / .ppt)，按「幻灯片序号」输出可索引文本。
 *
 * <p>纯图片幻灯片没有原生文本框，早期实现只会留下「幻灯片 N」这类编号，
 * 导入虽然成功但索引里没有任何正文。这里对这类幻灯片先渲染成位图，
 * 再交给 {@link OcrEngine} 识别，并把原生文本与 OCR 文本按行去重合并。
 * 每个幻灯片单独成块并携带页码，便于问答时引用来源。</p>
 */
@Component
public class PowerPointDocumentParser implements DocumentParser {

    /** 解析结果的文档类型，由 {@link PptSlideChunker} 消费。 */
    public static final String DOCUMENT_TYPE = "pptx";
    /** 幻灯片块的块类型，每个块对应一页幻灯片。 */
    public static final String SLIDE_BLOCK_TYPE = "ppt-slide";

    private static final Logger log = LoggerFactory.getLogger(PowerPointDocumentParser.class);
    private static final int MIN_MEANINGFUL_SLIDE_CHARACTERS = 10;
    private static final long MAX_RENDER_PIXELS = 25_000_000L;
    private static final int DEFAULT_RENDER_WIDTH = 1600;

    private final OcrEngine ocrEngine;
    private final SlideOcrMode slideOcrMode;
    private final int renderWidth;
    private final int maxSlides;
    private final int maxOcrSlides;

    /**
     * 创建支持纯图片幻灯片 OCR 的 PowerPoint 解析器。
     *
     * @param ocrEngine OCR 引擎
     * @param slideOcrMode OCR 触发策略：auto 仅识别正文不足的幻灯片，always 每页都识别，never 关闭
     * @param renderWidth 幻灯片渲染目标宽度（像素）
     * @param maxSlides 单个文件最大幻灯片数
     * @param maxOcrSlides 单个文件最大 OCR 幻灯片数
     */
    @Autowired
    public PowerPointDocumentParser(
            OcrEngine ocrEngine,
            @Value("${workbench.ocr.ppt-slide-mode:auto}") String slideOcrMode,
            @Value("${workbench.ocr.ppt-render-width:1600}") int renderWidth,
            @Value("${workbench.ocr.max-slides:500}") int maxSlides,
            @Value("${workbench.ocr.max-ocr-slides:200}") int maxOcrSlides
    ) {
        this.ocrEngine = ocrEngine;
        this.slideOcrMode = SlideOcrMode.parse(slideOcrMode);
        this.renderWidth = renderWidth <= 0 ? DEFAULT_RENDER_WIDTH : renderWidth;
        this.maxSlides = Math.max(1, maxSlides);
        this.maxOcrSlides = Math.max(0, maxOcrSlides);
    }

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
            } catch (IOException exception) {
                throw parsingFailure(exception);
            } catch (RuntimeException exception) {
                throw parsingFailure(exception);
            }
        }
        try (HSLFSlideShow presentation = new HSLFSlideShow(new ByteArrayInputStream(content))) {
            return parseLegacy(presentation);
        } catch (IOException exception) {
            throw parsingFailure(exception);
        } catch (RuntimeException exception) {
            throw parsingFailure(exception);
        }
    }

    private ParsedDocument parseModern(XMLSlideShow presentation) {
        List<XSLFSlide> slides = presentation.getSlides();
        validateSlideCount(slides.size());
        Dimension pageSize = presentation.getPageSize();

        StringBuilder fullText = new StringBuilder();
        List<DocumentBlock> blocks = new ArrayList<>();
        int slidesWithBody = 0;
        int ocrSlides = 0;

        for (int index = 0; index < slides.size(); index++) {
            int slideNumber = index + 1;
            XSLFSlide slide = slides.get(index);
            List<String> nativeLines = new ArrayList<>();
            for (XSLFShape shape : slide.getShapes()) {
                collectText(shape, nativeLines);
            }

            String ocrText = "";
            if (shouldOcr(nativeLines)) {
                ensureOcrBudget(++ocrSlides);
                ocrText = recognize(renderSlide(pageSize, slide, slideNumber), slideNumber);
            }

            List<String> merged = mergeLines(nativeLines, ocrText);
            addSlideBlock(fullText, blocks, slideNumber, merged);
            if (!merged.isEmpty()) {
                slidesWithBody++;
            }
        }

        return finish(fullText, blocks, slidesWithBody);
    }

    private ParsedDocument parseLegacy(HSLFSlideShow presentation) {
        List<HSLFSlide> slides = presentation.getSlides();
        validateSlideCount(slides.size());
        Dimension pageSize = presentation.getPageSize();

        StringBuilder fullText = new StringBuilder();
        List<DocumentBlock> blocks = new ArrayList<>();
        int slidesWithBody = 0;
        int ocrSlides = 0;

        for (int index = 0; index < slides.size(); index++) {
            int slideNumber = index + 1;
            HSLFSlide slide = slides.get(index);
            List<String> nativeLines = new ArrayList<>();
            for (List<HSLFTextParagraph> paragraphGroup : slide.getTextParagraphs()) {
                String text = HSLFTextParagraph.getText(paragraphGroup);
                if (text != null) {
                    for (String line : text.split("\\R")) {
                        if (!line.isBlank()) {
                            nativeLines.add(line.strip());
                        }
                    }
                }
            }

            String ocrText = "";
            if (shouldOcr(nativeLines)) {
                ensureOcrBudget(++ocrSlides);
                ocrText = recognize(renderSlide(pageSize, slide, slideNumber), slideNumber);
            }

            List<String> merged = mergeLines(nativeLines, ocrText);
            addSlideBlock(fullText, blocks, slideNumber, merged);
            if (!merged.isEmpty()) {
                slidesWithBody++;
            }
        }

        return finish(fullText, blocks, slidesWithBody);
    }

    private ParsedDocument finish(StringBuilder fullText, List<DocumentBlock> blocks, int slidesWithBody) {
        if (slidesWithBody == 0) {
            throw new IllegalArgumentException(
                    "PowerPoint 未提取到可索引正文（仅识别到幻灯片编号）。如果这是纯图片演示文稿，请确认 OCR 服务可用，"
                            + "或调整 workbench.ocr.ppt-slide-mode 配置。");
        }
        return new ParsedDocument(DOCUMENT_TYPE, fullText.toString(), null, blocks);
    }

    private void validateSlideCount(int slideCount) {
        if (slideCount > maxSlides) {
            throw new IllegalArgumentException("PowerPoint 幻灯片数超过限制（最多 " + maxSlides + " 页）");
        }
    }

    private void ensureOcrBudget(int ocrSlides) {
        if (ocrSlides > maxOcrSlides) {
            throw new IllegalArgumentException("PowerPoint OCR 幻灯片数超过限制（最多 " + maxOcrSlides + " 页）");
        }
    }

    private boolean shouldOcr(List<String> nativeLines) {
        return switch (slideOcrMode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> meaningfulCharacters(String.join("", nativeLines)) < MIN_MEANINGFUL_SLIDE_CHARACTERS;
        };
    }

    /**
     * 递归提取文本框内容。组合形状和表格不会直接暴露为文本形状，需要显式下钻，
     * 否则嵌在组合里的标题同样会丢失。
     */
    private void collectText(XSLFShape shape, List<String> lines) {
        if (shape instanceof XSLFTextShape textShape) {
            addText(textShape.getText(), lines);
            return;
        }
        if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape child : group.getShapes()) {
                collectText(child, lines);
            }
            return;
        }
        if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                for (XSLFTableCell cell : row.getCells()) {
                    addText(cell.getText(), lines);
                }
            }
        }
    }

    private void addText(String text, List<String> lines) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
    }

    private String recognize(BufferedImage image, int slideNumber) {
        if (image == null) {
            return "";
        }
        try {
            String text = ocrEngine.recognize(image);
            return text == null ? "" : text.strip();
        } catch (RuntimeException exception) {
            // 单页 OCR 失败不应让整份演示文稿导入失败：保留原生文本继续处理。
            log.warn("PowerPoint 幻灯片 OCR 失败 slide={} error={}", slideNumber, exception.getMessage());
            return "";
        }
    }

    /**
     * 把幻灯片渲染成位图。POI 按「点」绘制，因此先用 scale 变换把图形放大到目标像素宽度。
     */
    private BufferedImage renderSlide(Dimension pageSize, XSLFSlide slide, int slideNumber) {
        try {
            return drawSlide(pageSize, graphics -> slide.draw(graphics), slideNumber);
        } catch (RuntimeException exception) {
            log.warn("PowerPoint 幻灯片渲染失败 slide={} error={}", slideNumber, exception.getMessage());
            return null;
        }
    }

    private BufferedImage renderSlide(Dimension pageSize, HSLFSlide slide, int slideNumber) {
        try {
            return drawSlide(pageSize, graphics -> slide.draw(graphics), slideNumber);
        } catch (RuntimeException exception) {
            log.warn("PowerPoint 幻灯片渲染失败 slide={} error={}", slideNumber, exception.getMessage());
            return null;
        }
    }

    private BufferedImage drawSlide(Dimension pageSize, SlidePainter painter, int slideNumber) {
        if (pageSize == null || pageSize.getWidth() <= 0 || pageSize.getHeight() <= 0) {
            throw new IllegalArgumentException("PowerPoint 第 " + slideNumber + " 页尺寸无效");
        }
        double width = pageSize.getWidth();
        double height = pageSize.getHeight();
        double scale = renderWidth / width;
        if (width * scale * height * scale > MAX_RENDER_PIXELS) {
            scale = Math.sqrt(MAX_RENDER_PIXELS / (width * height));
        }
        int imageWidth = Math.max(1, (int) Math.round(width * scale));
        int imageHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, imageWidth, imageHeight);
            graphics.scale(scale, scale);
            painter.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * 原生文本优先，OCR 只补充原生文本没有覆盖到的行。
     * 这样既能拿到图片里的正文，又不会把文本框内容重复索引一遍。
     */
    private List<String> mergeLines(List<String> nativeLines, String ocrText) {
        List<String> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : nativeLines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            merged.add(trimmed);
            String key = dedupKey(trimmed);
            if (!key.isEmpty()) {
                seen.add(key);
            }
        }
        if (ocrText == null || ocrText.isBlank()) {
            return merged;
        }
        for (String rawLine : ocrText.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            String key = dedupKey(line);
            if (key.isEmpty() || isCovered(key, seen)) {
                continue;
            }
            merged.add(line);
            seen.add(key);
        }
        return merged;
    }

    private boolean isCovered(String key, Set<String> seen) {
        for (String existing : seen) {
            if (existing.equals(key)) {
                return true;
            }
            // OCR 常把一段话拆成多行，只要被原生文本完整包含就视为重复。
            if (existing.length() > key.length() && existing.contains(key)) {
                return true;
            }
            if (key.length() > existing.length() && existing.length() >= 6 && key.contains(existing)) {
                return true;
            }
        }
        return false;
    }

    private String dedupKey(String line) {
        StringBuilder key = new StringBuilder();
        line.codePoints().filter(Character::isLetterOrDigit).forEach(key::appendCodePoint);
        return key.toString().toLowerCase(Locale.ROOT);
    }

    private long meaningfulCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    private void addSlideBlock(StringBuilder fullText, List<DocumentBlock> blocks, int slideNumber, List<String> lines) {
        StringBuilder content = new StringBuilder("幻灯片 ").append(slideNumber);
        for (String line : lines) {
            content.append('\n').append(line);
        }
        if (!fullText.isEmpty()) {
            fullText.append("\n\n");
        }
        int start = fullText.length();
        fullText.append(content);
        blocks.add(new DocumentBlock(content.toString(), SLIDE_BLOCK_TYPE, "", 0, start, fullText.length(), slideNumber));
    }

    private IllegalArgumentException parsingFailure(RuntimeException exception) {
        if (exception instanceof IllegalArgumentException illegal
                && illegal.getMessage() != null
                && illegal.getMessage().startsWith("PowerPoint")) {
            return illegal;
        }
        return new IllegalArgumentException("PowerPoint 文件已损坏或无法解析", exception);
    }

    private IllegalArgumentException parsingFailure(IOException exception) {
        return new IllegalArgumentException("PowerPoint 文件已损坏或无法解析", exception);
    }

    private boolean isZip(byte[] content) {
        return content.length > 4
                && content[0] == (byte) 0x50 && content[1] == (byte) 0x4B
                && content[2] == (byte) 0x03 && content[3] == (byte) 0x04;
    }

    /** 幻灯片 OCR 触发策略。 */
    private enum SlideOcrMode {
        AUTO, ALWAYS, NEVER;

        static SlideOcrMode parse(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return AUTO;
            }
        }
    }

    @FunctionalInterface
    private interface SlidePainter {
        void paint(Graphics2D graphics);
    }
}
