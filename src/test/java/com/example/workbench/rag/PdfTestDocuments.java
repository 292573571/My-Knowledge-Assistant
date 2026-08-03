package com.example.workbench.rag;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

final class PdfTestDocuments {

    private PdfTestDocuments() {
    }

    static byte[] textPdf(String title, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.getDocumentInformation().setTitle(title);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(50, 750);
                    for (String line : List.of(text.split("\\n"))) {
                        stream.showText(line);
                        stream.newLineAtOffset(0, -18);
                    }
                    stream.endText();
                }
            }
            return save(document);
        }
    }

    static byte[] scannedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(LosslessFactory.createFromImage(document, image), 50, 650, 100, 100);
            }
            return save(document);
        }
    }

    static byte[] mixedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage textPage = new PDPage();
            document.addPage(textPage);
            try (PDPageContentStream stream = new PDPageContentStream(document, textPage)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 750);
                stream.showText("Native text page knowledge");
                stream.endText();
            }

            PDPage scannedPage = new PDPage();
            document.addPage(scannedPage);
            BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            try (PDPageContentStream stream = new PDPageContentStream(document, scannedPage)) {
                stream.drawImage(LosslessFactory.createFromImage(document, image), 50, 650, 100, 100);
            }
            return save(document);
        }
    }

    static byte[] repeatedLinePdf(String repeatedLine, String... uniqueLines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String uniqueLine : uniqueLines) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(50, 750);
                    stream.showText(repeatedLine);
                    if (!uniqueLine.isBlank()) {
                        stream.newLineAtOffset(0, -18);
                        stream.showText(uniqueLine);
                    }
                    stream.endText();
                }
            }
            return save(document);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }
}
