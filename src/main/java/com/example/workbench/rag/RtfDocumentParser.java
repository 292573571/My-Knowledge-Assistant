package com.example.workbench.rag;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.rtf.RTFEditorKit;
import org.springframework.stereotype.Component;

/**
 * 解析 RTF(富文本),借助 JDK 自带的 RTFEditorKit 提取纯文本。复用 TextParagraphChunker(text 类型)。
 * 无需引入新库。
 */
@Component
public class RtfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".rtf");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        try {
            RTFEditorKit kit = new RTFEditorKit();
            DefaultStyledDocument document = new DefaultStyledDocument();
            kit.read(new ByteArrayInputStream(content), document, 0);
            String text = document.getText(0, document.getLength());
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("RTF 未提取到可索引文本");
            }
            StringBuilder fullText = new StringBuilder();
            List<DocumentBlock> blocks = new ArrayList<>();
            for (String paragraph : text.split("\\R+")) {
                String trimmed = paragraph.strip();
                if (!trimmed.isBlank()) {
                    if (!fullText.isEmpty()) {
                        fullText.append("\n\n");
                    }
                    int start = fullText.length();
                    fullText.append(trimmed);
                    blocks.add(new DocumentBlock(trimmed, "text-paragraph", "", 0, start, fullText.length()));
                }
            }
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("RTF 未提取到可索引文本");
            }
            return new ParsedDocument("text", fullText.toString(), null, blocks);
        } catch (IllegalArgumentException illegalArgumentException) {
            throw illegalArgumentException;
        } catch (Exception exception) {
            throw new IllegalArgumentException("RTF 文件格式无效或无法解析", exception);
        }
    }
}
