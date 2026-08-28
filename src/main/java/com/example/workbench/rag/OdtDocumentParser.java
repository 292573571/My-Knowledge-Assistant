package com.example.workbench.rag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 解析 OpenDocument 文本(.odt, LibreOffice/OpenOffice)。ODT 是 ZIP 包,内含 content.xml,
 * 直接解压并用 JDK 内置 DOM 提取段落文本,无需引入额外依赖。复用 TextParagraphChunker(text 类型)。
 */
@Component
public class OdtDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".odt");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        try {
            String xml = extractContentXml(content);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            XPath xpath = XPathFactory.newInstance().newXPath();
            NodeList paragraphs = (NodeList) xpath.evaluate("//*[local-name()='p']", document, XPathConstants.NODESET);
            StringBuilder fullText = new StringBuilder();
            List<DocumentBlock> blocks = new ArrayList<>();
            for (int index = 0; index < paragraphs.getLength(); index++) {
                Node paragraph = paragraphs.item(index);
                String text = paragraph == null || paragraph.getTextContent() == null
                        ? "" : paragraph.getTextContent().replaceAll("\\s+", " ").strip();
                if (!text.isBlank()) {
                    if (!fullText.isEmpty()) {
                        fullText.append("\n\n");
                    }
                    int start = fullText.length();
                    fullText.append(text);
                    blocks.add(new DocumentBlock(text, "text-paragraph", "", 0, start, fullText.length()));
                }
            }
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("ODT 未提取到可索引文本");
            }
            return new ParsedDocument("text", fullText.toString(), null, blocks);
        } catch (IllegalArgumentException illegalArgumentException) {
            throw illegalArgumentException;
        } catch (Exception exception) {
            throw new IllegalArgumentException("ODT 文件已损坏或无法解析", exception);
        }
    }

    private String extractContentXml(byte[] content) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("content.xml".equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    return out.toString(StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException("ODT 缺少 content.xml");
    }
}
