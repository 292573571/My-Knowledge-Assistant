package com.example.workbench.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 提取 PDF 的逐页文本，并对疑似扫描页执行 OCR。
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int MIN_MEANINGFUL_PAGE_CHARACTERS = 10;
    private static final float OCR_RENDER_DPI = 300;
    private static final int MIN_PAGES_FOR_REPEATED_LINE_FILTER = 3;
    private static final int MAX_REPEATED_LINE_CHARACTERS = 80;
    private final OcrEngine ocrEngine;
    private final int maxPages;
    private final int maxOcrPages;
    private final float maxPageWidthPoints;
    private final float maxPageHeightPoints;
    private final long maxOcrPixels;

    /**
     * 创建支持扫描页识别的 PDF 解析器。
     *
     * @param ocrEngine 扫描页 OCR 引擎
     */
    public PdfDocumentParser(OcrEngine ocrEngine) {
        this(ocrEngine, 200, 50, 14400, 14400, 25_000_000L);
    }

    /**
     * 创建带有页面和 OCR 资源限制的 PDF 解析器。
     *
     * @param ocrEngine 扫描页 OCR 引擎
     * @param maxPages PDF 最大页数
     * @param maxOcrPages 单个 PDF 最大 OCR 页数
     * @param maxPageWidthPoints OCR 页面最大宽度（PDF 点）
     * @param maxPageHeightPoints OCR 页面最大高度（PDF 点）
     * @param maxOcrPixels OCR 渲染页面估算的最大像素数
     */
    @Autowired
    public PdfDocumentParser(OcrEngine ocrEngine,
            @Value("${workbench.ocr.max-pages:200}") int maxPages,
            @Value("${workbench.ocr.max-ocr-pages:50}") int maxOcrPages,
            @Value("${workbench.ocr.max-page-width-points:14400}") float maxPageWidthPoints,
            @Value("${workbench.ocr.max-page-height-points:14400}") float maxPageHeightPoints,
            @Value("${workbench.ocr.max-pixels:25000000}") long maxOcrPixels) {
        this.ocrEngine = ocrEngine;
        this.maxPages = maxPages;
        this.maxOcrPages = maxOcrPages;
        this.maxPageWidthPoints = maxPageWidthPoints;
        this.maxPageHeightPoints = maxPageHeightPoints;
        this.maxOcrPixels = maxOcrPixels;
    }

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        if (content.length < 5 || content[0] != '%' || content[1] != 'P'
                || content[2] != 'D' || content[3] != 'F' || content[4] != '-') {
            throw new IllegalArgumentException("文件不是有效的 PDF");
        }

        try (PDDocument pdf = Loader.loadPDF(content)) {
            if (pdf.isEncrypted()) {
                throw new IllegalArgumentException("暂不支持加密 PDF");
            }
            if (pdf.getNumberOfPages() > maxPages) {
                throw new IllegalArgumentException("PDF 页数超过限制");
            }

            List<PageText> pages = filterRepeatedPageLines(extractPages(pdf));

            StringBuilder fullText = new StringBuilder();
            List<DocumentBlock> blocks = new ArrayList<>();
            for (PageText page : pages) {
                if (page.text().isBlank()) {
                    continue;
                }
                if (!fullText.isEmpty()) {
                    fullText.append("\n\n");
                }
                int start = fullText.length();
                fullText.append(page.text());
                blocks.add(new DocumentBlock(
                        page.text(), "pdf-page", "", 0, start, fullText.length(), page.pageNumber()
                ));
            }
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("PDF 未提取到可索引文本");
            }

            String title = pdf.getDocumentInformation().getTitle();
            return new ParsedDocument(
                    "pdf", fullText.toString(), title == null || title.isBlank() ? null : title.strip(), blocks
            );
        } catch (IOException exception) {
            throw new IllegalArgumentException("PDF 文件已损坏或无法解析", exception);
        }
    }

    private List<PageText> extractPages(PDDocument pdf) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        PDFRenderer renderer = new PDFRenderer(pdf);
        List<PageText> pages = new ArrayList<>();
        int ocrPages = 0;
        for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String text = stripper.getText(pdf).strip();
            boolean hasImage = containsImage(pdf.getPage(pageNumber - 1).getResources());
            if ((hasImage && meaningfulCharacters(text) < MIN_MEANINGFUL_PAGE_CHARACTERS)
                    || hasUnusableTextLayer(text)) {
                PDPage page = pdf.getPage(pageNumber - 1);
                if (++ocrPages > maxOcrPages) throw new IllegalArgumentException("PDF OCR 页数超过限制");
                validateOcrPageSize(page, pageNumber);
                text = ocrEngine.recognize(renderer.renderImageWithDPI(pageNumber - 1, OCR_RENDER_DPI, ImageType.RGB)).strip();
                if (text.isBlank()) {
                    throw new IllegalArgumentException("PDF 第 " + pageNumber + " 页 OCR 未识别到文字");
                }
            }
            pages.add(new PageText(pageNumber, text));
        }
        return pages;
    }

    private void validateOcrPageSize(PDPage page, int pageNumber) {
        float width = page.getMediaBox().getWidth();
        float height = page.getMediaBox().getHeight();
        double pixels = width / 72 * OCR_RENDER_DPI * height / 72 * OCR_RENDER_DPI;
        if (width <= 0 || height <= 0 || width > maxPageWidthPoints || height > maxPageHeightPoints || pixels > maxOcrPixels) {
            throw new IllegalArgumentException("PDF 第 " + pageNumber + " 页尺寸或 OCR 像素数超过限制");
        }
    }

    /**
     * 递归检查页面及 Form XObject 资源中的图片，用于区分空白页与扫描候选页。
     */
    private boolean containsImage(PDResources resources) throws IOException {
        if (resources == null) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject) {
                return true;
            }
            if (object instanceof PDFormXObject form && containsImage(form.getResources())) {
                return true;
            }
        }
        return false;
    }

    private long meaningfulCharacters(String text) {
        return text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    private boolean hasUnusableTextLayer(String text) {
        if (meaningfulCharacters(text) < MIN_MEANINGFUL_PAGE_CHARACTERS) {
            return false;
        }
        long invalidCharacters = text.codePoints()
                .filter(codePoint -> codePoint == 0xFFFD || Character.isISOControl(codePoint)
                        && codePoint != '\n' && codePoint != '\r' && codePoint != '\t'
                        || Character.getType(codePoint) == Character.PRIVATE_USE)
                .count();
        if (invalidCharacters > 0) {
            return true;
        }

        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        if (lines.size() >= 8) {
            long sparseLines = lines.stream().filter(line -> meaningfulCharacters(line) <= 4).count();
            double averageMeaningfulCharacters = meaningfulCharacters(text) / (double) lines.size();
            long fragmentedLines = lines.stream().filter(line -> meaningfulCharacters(line) <= 2).count();
            if (sparseLines >= Math.ceil(lines.size() * 0.6) && averageMeaningfulCharacters < 8) {
                return true;
            }
            // 某些中文 PDF 前面的段落仍能正常提取，但列表项或单字会被错误拆成多行。
            // 仅看全文平均长度会掩盖这种损坏，因此额外检测短行的密集程度。
            if (fragmentedLines >= Math.ceil(lines.size() * 0.45)
                    && averageMeaningfulCharacters < 20) {
                return true;
            }
        }

        List<String> tokens = List.of(text.split("\\s+")).stream().filter(token -> !token.isBlank()).toList();
        if (tokens.size() < 16) {
            return false;
        }
        long fragmentedTokens = tokens.stream()
                .filter(token -> token.codePoints().filter(Character::isLetterOrDigit).count() <= 2)
                .count();
        return fragmentedTokens >= Math.ceil(tokens.size() * 0.7);
    }

    private List<PageText> filterRepeatedPageLines(List<PageText> pages) {
        if (pages.size() < MIN_PAGES_FOR_REPEATED_LINE_FILTER) {
            return pages;
        }
        Map<String, Integer> pageOccurrences = new HashMap<>();
        Map<Integer, Set<String>> pageLines = new HashMap<>();
        for (PageText page : pages) {
            Set<String> linesOnPage = new HashSet<>();
            for (String line : page.text().split("\\R")) {
                String normalized = normalizedRepeatedLine(line);
                if (!normalized.isEmpty()) {
                    linesOnPage.add(normalized);
                }
            }
            pageLines.put(page.pageNumber(), linesOnPage);
            linesOnPage.forEach(line -> pageOccurrences.merge(line, 1, Integer::sum));
        }

        int minimumOccurrences = Math.max(3, (int) Math.ceil(pages.size() * 0.6));
        Set<String> repeatedLines = new HashSet<>();
        pageOccurrences.forEach((line, count) -> {
            boolean appearsOnlyAlongsideOtherText = pages.stream()
                    .filter(page -> pageLines.get(page.pageNumber()).contains(line))
                    .allMatch(page -> pageLines.get(page.pageNumber()).size() > 1);
            if (count >= minimumOccurrences && appearsOnlyAlongsideOtherText) {
                repeatedLines.add(line);
            }
        });
        if (repeatedLines.isEmpty()) {
            return pages;
        }

        return pages.stream()
                .map(page -> new PageText(page.pageNumber(), page.text().lines()
                        .filter(line -> !repeatedLines.contains(normalizedRepeatedLine(line)))
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("")
                        .strip()))
                .toList();
    }

    private String normalizedRepeatedLine(String line) {
        String normalized = line.codePoints()
                .filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .toLowerCase(Locale.ROOT);
        int characters = normalized.codePointCount(0, normalized.length());
        return characters >= 2 && characters <= MAX_REPEATED_LINE_CHARACTERS ? normalized : "";
    }

    private record PageText(int pageNumber, String text) {
    }
}
