package com.example.workbench.rag;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 解析 XML,提取各元素下直接文本节点(跳过 script/style),生成「标签: 文本」块。
 * 复用 TextParagraphChunker(text 类型)。使用 JDK 内置 DOM,无需新库。
 */
@Component
public class XmlDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xml");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(content));
            List<String> texts = new ArrayList<>();
            if (document.getDocumentElement() != null) {
                traverse(document.getDocumentElement(), texts);
            }
            if (texts.isEmpty()) {
                throw new IllegalArgumentException("XML 未提取到可索引文本");
            }
            StringBuilder fullText = new StringBuilder();
            List<DocumentBlock> blocks = new ArrayList<>();
            for (String text : texts) {
                addBlock(fullText, blocks, text);
            }
            return new ParsedDocument("text", fullText.toString(), null, blocks);
        } catch (IllegalArgumentException illegalArgumentException) {
            throw illegalArgumentException;
        } catch (Exception exception) {
            throw new IllegalArgumentException("XML 文件格式无效或无法解析", exception);
        }
    }

    private void traverse(Node node, List<String> texts) {
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return;
        }
        String localName = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
        if ("script".equalsIgnoreCase(localName) || "style".equalsIgnoreCase(localName)) {
            return;
        }
        StringBuilder elementText = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.TEXT_NODE) {
                elementText.append(child.getTextContent());
            } else if (child.getNodeType() == Node.CDATA_SECTION_NODE) {
                elementText.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                traverse(child, texts);
            }
        }
        String trimmed = elementText.toString().replaceAll("\\s+", " ").strip();
        if (!trimmed.isBlank()) {
            texts.add(localName + ": " + trimmed);
        }
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
}
