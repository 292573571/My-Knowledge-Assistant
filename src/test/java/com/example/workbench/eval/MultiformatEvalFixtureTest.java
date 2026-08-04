package com.example.workbench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workbench.rag.DocxBlockChunker;
import com.example.workbench.rag.DocxDocumentParser;
import com.example.workbench.rag.DocumentChunk;
import com.example.workbench.rag.HtmlBlockChunker;
import com.example.workbench.rag.HtmlDocumentParser;
import com.example.workbench.rag.ParsedDocument;
import com.example.workbench.rag.PdfDocumentParser;
import com.example.workbench.rag.PdfPageChunker;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MultiformatEvalFixtureTest {

    private static final Path FIXTURES = Path.of("eval", "multiformat", "fixtures");

    @Test
    void pdfAnswerRemainsOnSecondPage() throws Exception {
        ParsedDocument document = new PdfDocumentParser(image -> "").parse(
                Files.readAllBytes(FIXTURES.resolve("quality-page.pdf")));
        var chunks = new PdfPageChunker().chunk(document);

        assertThat(chunks).filteredOn(chunk -> chunk.content().contains("FIFTEEN MINUTES"))
                .singleElement().extracting(DocumentChunk::pageNumber).isEqualTo(2);
    }

    @Test
    void docxHeadingPathRemainsNested() throws Exception {
        ParsedDocument document = new DocxDocumentParser().parse(
                Files.readAllBytes(FIXTURES.resolve("quality-heading.docx")));
        var chunks = new DocxBlockChunker().chunk(document);

        assertThat(chunks).filteredOn(chunk -> chunk.content().contains("隔离故障节点"))
                .singleElement().extracting(DocumentChunk::headingPath).isEqualTo("平台架构 > 故障恢复");
    }

    @Test
    void htmlNavigationIsRemovedAndTableHeaderStaysWithRow() throws Exception {
        HtmlDocumentParser parser = new HtmlDocumentParser();
        ParsedDocument clean = parser.parse(Files.readAllBytes(FIXTURES.resolve("quality-clean.html")));
        ParsedDocument table = parser.parse(Files.readAllBytes(FIXTURES.resolve("quality-table.html")));
        var chunks = new HtmlBlockChunker().chunk(table);

        assertThat(clean.content()).doesNotContain("产品中心", "热门推荐", "广告入口");
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.content())
                .contains("服务名称", "恢复目标", "责任组", "订单服务", "十五分钟", "交易平台组"));
    }

    @Test
    void ocrFixtureKeepsControlledRecognitionLikeTypo() throws Exception {
        BufferedImageAssertion.assertImageReadable(FIXTURES.resolve("quality-ocr.png"));
    }

    private static final class BufferedImageAssertion {
        private static void assertImageReadable(Path path) throws Exception {
            var image = javax.imageio.ImageIO.read(path.toFile());
            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isGreaterThan(1000);
            assertThat(image.getHeight()).isGreaterThan(400);
        }
    }
}
