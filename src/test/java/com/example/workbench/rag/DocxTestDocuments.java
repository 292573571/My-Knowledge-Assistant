package com.example.workbench.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

final class DocxTestDocuments {

    private DocxTestDocuments() {
    }

    static byte[] structuredDocument() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.getProperties().getCoreProperties().setTitle("Architecture Guide");
            paragraph(document, "Heading 1", "Architecture");
            paragraph(document, null, "Introduction before table");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Component");
            table.getRow(0).getCell(1).setText("Purpose");
            table.getRow(1).getCell(0).setText("Parser");
            table.getRow(1).getCell(1).setText("Keep body order");

            paragraph(document, "Heading 2", "Processing");
            BigInteger numberId = decimalNumbering(document);
            listItem(document, numberId, "Parse blocks");
            listItem(document, numberId, "Create chunks");
            paragraph(document, null, "Conclusion after list");
            return save(document);
        }
    }

    static byte[] simpleDocument(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            paragraph(document, "Heading 1", "Document");
            paragraph(document, null, text);
            return save(document);
        }
    }

    private static void paragraph(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        if (style != null) {
            paragraph.setStyle(style);
        }
        paragraph.createRun().setText(text);
    }

    private static BigInteger decimalNumbering(XWPFDocument document) {
        XWPFNumbering numbering = document.createNumbering();
        CTAbstractNum definition = CTAbstractNum.Factory.newInstance();
        definition.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = definition.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewStart().setVal(BigInteger.ONE);
        level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
        level.addNewLvlText().setVal("%1.");
        BigInteger abstractId = numbering.addAbstractNum(new XWPFAbstractNum(definition));
        return numbering.addNum(abstractId);
    }

    private static void listItem(XWPFDocument document, BigInteger numberId, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setNumID(numberId);
        paragraph.setNumILvl(BigInteger.ZERO);
        paragraph.createRun().setText(text);
    }

    private static byte[] save(XWPFDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        return output.toByteArray();
    }
}
