package com.example.workbench.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * 提取文本型 PDF 的逐页文本。检测到扫描页时拒绝整份文档，避免建立不完整索引。
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int MIN_MEANINGFUL_PAGE_CHARACTERS = 10;

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

            List<PageText> pages = extractPages(pdf);
            List<Integer> scannedPages = pages.stream()
                    .filter(page -> page.hasImage() && meaningfulCharacters(page.text()) < MIN_MEANINGFUL_PAGE_CHARACTERS)
                    .map(PageText::pageNumber)
                    .toList();
            if (!scannedPages.isEmpty()) {
                throw new IllegalArgumentException(
                        "PDF 第 " + scannedPages.get(0) + " 页疑似扫描页，当前版本不支持 OCR"
                );
            }

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
        List<PageText> pages = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String text = stripper.getText(pdf).strip();
            pages.add(new PageText(pageNumber, text, containsImage(pdf.getPage(pageNumber - 1).getResources())));
        }
        return pages;
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

    private record PageText(int pageNumber, String text, boolean hasImage) {
    }
}
