package com.example.workbench.eval;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * 生成多格式检索评测所需的固定 PDF、DOCX 和 OCR 图片样本。
 */
public final class MultiformatEvalFixtureGenerator {

    private static final Path OUTPUT_DIRECTORY = Path.of("eval", "multiformat", "fixtures");

    private MultiformatEvalFixtureGenerator() {
    }

    /**
     * 生成并覆盖多格式二进制评测样本。
     *
     * @param args 未使用
     * @throws Exception 样本无法写入时抛出
     */
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT_DIRECTORY);
        writePdf();
        writeDocx();
        writeOcrImage();
    }

    private static void writePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle("Starship Release Manual");
            document.setDocumentInformation(information);
            addPdfPage(document, "STARSHIP RELEASE MANUAL", "Page one: deployment prerequisites and rollback preparation.");
            addPdfPage(document, "ORDER SERVICE RECOVERY", "ORDER SERVICE RECOVERY TARGET: FIFTEEN MINUTES");
            addPdfPage(document, "AUDIT AND REVIEW", "Page three: review logs and verify the release report.");
            document.save(OUTPUT_DIRECTORY.resolve("quality-page.pdf").toFile());
        }
    }

    private static void addPdfPage(PDDocument document, String heading, String body) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
            content.newLineAtOffset(72, 760);
            content.showText(heading);
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(0, -42);
            content.showText(body);
            content.endText();
        }
    }

    private static void writeDocx() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.getProperties().getCoreProperties().setTitle("星舟架构指南");
            paragraph(document, "平台架构", "Heading1", 20);
            paragraph(document, "平台由接入层、交易层和存储层组成。", null, 12);
            paragraph(document, "故障恢复", "Heading2", 16);
            paragraph(document, "发生故障后先隔离故障节点，再切换只读副本。", null, 12);
            try (var output = Files.newOutputStream(OUTPUT_DIRECTORY.resolve("quality-heading.docx"))) {
                document.write(output);
            }
        }
    }

    private static void paragraph(XWPFDocument document, String text, String style, int fontSize) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        if (style != null) {
            paragraph.setStyle(style);
        }
        var run = paragraph.createRun();
        run.setFontFamily("PingFang SC");
        run.setFontSize(fontSize);
        run.setText(text);
    }

    private static void writeOcrImage() throws IOException {
        BufferedImage image = new BufferedImage(1800, 620, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
            graphics.drawString("WAREHOUSE INVENTORY CARD", 120, 170);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 62));
            graphics.drawString("Warehouse code: C-I7", 120, 330);
            graphics.drawString("Inventory cycle: EVERY FOURTEEN DAYS", 120, 470);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", OUTPUT_DIRECTORY.resolve("quality-ocr.png").toFile());
    }
}
