package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.workbench.workspace.DocumentVisibility;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.example.workbench.workspace.WorkspaceType;

class DocumentIngestionServiceSyncTest {

    @TempDir
    Path tempDir;

    private Path docsDirectory;
    private InMemoryVectorStore vectorStore;
    private DocumentIndexStore indexStore;
    private DocumentIngestionService service;

    @BeforeEach
    void setUp() throws Exception {
        docsDirectory = Files.createDirectories(tempDir.resolve("docs"));
        vectorStore = new InMemoryVectorStore();
        indexStore = new DocumentIndexStore(new ObjectMapper(), tempDir.resolve("document-index.json"));
        DocumentChunkerRouter router = new DocumentChunkerRouter(List.of(
                new MarkdownSmartChunker(),
                new TextParagraphChunker(),
                new PdfPageChunker(),
                new DocxBlockChunker(),
                new HtmlBlockChunker()
        ));
        service = new DocumentIngestionService(vectorStore, indexStore, parserRouter(), router, docsDirectory);
    }

    @Test
    void syncAddsUnchangedUpdatesAndDeletesDocumentsIncrementally() throws Exception {
        Path first = docsDirectory.resolve("first.md");
        Path second = docsDirectory.resolve("second.md");
        Files.writeString(first, "# First\n\nSpring AI uses ChatClient for model calls.");
        Files.writeString(second, "# Second\n\nRAG uses embedding and a vector store.");

        SyncResult firstSync = service.syncDocsDirectory();

        assertThat(firstSync.addedFiles()).isEqualTo(2);
        assertThat(firstSync.updatedFiles()).isZero();
        assertThat(firstSync.unchangedFiles()).isZero();
        assertThat(firstSync.deletedFiles()).isZero();
        assertThat(firstSync.addedChunks()).isPositive();
        assertThat(indexStore.list()).hasSize(2);

        SyncResult unchangedSync = service.syncDocsDirectory();

        assertThat(unchangedSync.addedFiles()).isZero();
        assertThat(unchangedSync.updatedFiles()).isZero();
        assertThat(unchangedSync.unchangedFiles()).isEqualTo(2);
        assertThat(unchangedSync.addedChunks()).isZero();

        Files.writeString(first, "# First\n\nSpring AI ChatClient supports token usage and finish reasons.");
        Files.delete(second);

        SyncResult changedSync = service.syncDocsDirectory();

        assertThat(changedSync.scannedFiles()).isEqualTo(1);
        assertThat(changedSync.updatedFiles()).isEqualTo(1);
        assertThat(changedSync.deletedFiles()).isEqualTo(1);
        assertThat(changedSync.addedChunks()).isPositive();
        assertThat(changedSync.deletedChunks()).isPositive();
        assertThat(indexStore.list()).hasSize(1);
        assertThat(vectorStore.similaritySearch("finish reasons", 5))
                .extracting(SourceDocument::fileName)
                .contains("first.md")
                .doesNotContain("second.md");
    }

    @Test
    void readsSourceFileOnlyFromDocsDirectory() throws Exception {
        Path source = Files.writeString(docsDirectory.resolve("用户指南.txt"), "source content");

        DocumentSourceFile result = service.sourceFile(source.toString(), "用户指南.txt");

        assertThat(result.fileName()).isEqualTo("用户指南.txt");
        assertThat(result.content()).isEqualTo("source content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.sourceFile(tempDir.resolve("outside.txt").toString(), "outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docs directory");
    }

    @Test
    void reportsMissingSourceFile() {
        assertThatThrownBy(() -> service.sourceFile(docsDirectory.resolve("missing.pdf").toString(), "missing.pdf"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value()).isEqualTo(404))
                .hasMessageContaining("文档源文件已缺失");
    }

    @Test
    void deletesPersistedDocumentByChunkIdsWithoutReplacingUnknownVectors() throws Exception {
        Path document = docsDirectory.resolve("persisted.md");
        Files.writeString(document, "# Persisted\n\nContent that was indexed before the process restarted.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);
        VectorStore restartedVectorStore = Mockito.mock(VectorStore.class);
        DocumentIngestionService restartedService = new DocumentIngestionService(
                restartedVectorStore,
                indexStore,
                parserRouter(),
                new DocumentChunkerRouter(List.of(
                        new MarkdownSmartChunker(), new TextParagraphChunker(), new PdfPageChunker(),
                        new DocxBlockChunker(), new HtmlBlockChunker()
                )),
                docsDirectory
        );

        restartedService.deleteDocument(entry.documentId(), "admin", true);

        verify(restartedVectorStore).deleteByIds(java.util.stream.IntStream.range(0, entry.chunkCount())
                .mapToObj(index -> entry.documentId() + "#chunk-" + index)
                .toList());
        verify(restartedVectorStore, never()).replaceAll(Mockito.anyList());
        assertThat(indexStore.list()).isEmpty();
    }

    private DocumentParserRouter parserRouter() {
        return new DocumentParserRouter(List.of(
                new MarkdownDocumentParser(), new TextDocumentParser(), new PdfDocumentParser(image -> "OCR text"),
                new DocxDocumentParser(), new HtmlDocumentParser(), new ImageDocumentParser(image -> "OCR text")
        ));
    }

    @Test
    void readsAnIndexedDocumentFromTheDocsDirectory() throws Exception {
        Path document = docsDirectory.resolve("readable.md");
        Files.writeString(document, "# Readable\n\nThis is the complete source content.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);

        DocumentContentResponse response = service.documentContent(entry.documentId(), "");

        assertThat(response.fileName()).isEqualTo("readable.md");
        assertThat(response.content()).contains("complete source content");
        assertThat(response.sourceAvailable()).isTrue();
    }

    @Test
    void recoversPreviewFromChromaWhenSourceFileIsMissing() throws Exception {
        Path document = docsDirectory.resolve("recoverable.md");
        Files.writeString(document, "# Recoverable\n\nIndexed content survives source loss.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);
        Files.delete(document);

        ChromaVectorStoreAdapter chroma = Mockito.mock(ChromaVectorStoreAdapter.class);
        Mockito.when(chroma.documentsByIds(Mockito.anyList())).thenReturn(List.of(
                recoveredChunk(entry, 0, "First indexed section ABC", 0, 25),
                recoveredChunk(entry, 1, "ABC second indexed section", 22, 48)
        ));
        DocumentIngestionService recoveredService = new DocumentIngestionService(
                chroma, indexStore, parserRouter(), new DocumentChunkerRouter(List.of(
                new MarkdownSmartChunker(), new TextParagraphChunker(), new PdfPageChunker(),
                new DocxBlockChunker(), new HtmlBlockChunker(), new ImageOcrChunker())), docsDirectory);

        DocumentContentResponse response = recoveredService.documentContent(entry.documentId(), "");

        assertThat(response.content()).isEqualTo("First indexed section ABC second indexed section");
        assertThat(response.sourceAvailable()).isFalse();
        verify(chroma).documentsByIds(java.util.stream.IntStream.range(0, entry.chunkCount())
                .mapToObj(index -> entry.documentId() + "#chunk-" + index).toList());
    }

    private SourceDocument recoveredChunk(DocumentIndexEntry entry, int chunkIndex, String content,
                                            int startOffset, int endOffset) {
        return new SourceDocument(entry.documentId() + "#chunk-" + chunkIndex, content, null,
                entry.fileName(), entry.path(), chunkIndex, entry.documentId(), entry.fileName(), entry.contentHash(),
                0, "", 0, startOffset, endOffset, "text-paragraph", entry.category(), entry.ownerUserId(),
                entry.workspaceId(), entry.visibility());
    }

    @Test
    void rejectsReadingAPrivateDocumentOwnedByAnotherUser() throws Exception {
        Path directory = Files.createDirectories(docsDirectory.resolve("manual-notes/user-2"));
        Files.writeString(directory.resolve("2026-07-27.md"), "# Private\n\nOnly user 2 can read this note.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);

        assertThatThrownBy(() -> service.documentContent(entry.documentId(), "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Indexed document not found");
    }

    @Test
    void listsOnlyPublicAndOwnedDocuments() throws Exception {
        Files.writeString(docsDirectory.resolve("public.md"), "# Public\n\nShared knowledge.");
        Path userOne = Files.createDirectories(docsDirectory.resolve("manual-notes/user-1"));
        Path userTwo = Files.createDirectories(docsDirectory.resolve("manual-notes/user-2"));
        Files.writeString(userOne.resolve("private-one.md"), "# One\n\nPrivate content for user one.");
        Files.writeString(userTwo.resolve("private-two.md"), "# Two\n\nPrivate content for user two.");
        service.syncDocsDirectory();

        assertThat(service.listVisibleIndexedDocuments("1"))
                .extracting(DocumentIndexEntry::fileName)
                .containsExactlyInAnyOrder("public.md", "private-one.md")
                .doesNotContain("private-two.md");
        assertThat(service.listPublicIndexedDocuments())
                .extracting(DocumentIndexEntry::fileName)
                .containsExactly("public.md");
        assertThat(service.listVisibleIndexedDocuments("1"))
                .allSatisfy(entry -> {
                    if (entry.ownerUserId().isBlank()) {
                        assertThat(entry.workspaceId()).isEqualTo("public-default");
                        assertThat(entry.visibility()).isEqualTo(DocumentVisibility.PUBLIC);
                    } else {
                        assertThat(entry.workspaceId()).isEqualTo("personal-1");
                        assertThat(entry.visibility()).isEqualTo(DocumentVisibility.PRIVATE);
                    }
                });
    }

    @Test
    void workspaceManagementListDoesNotMixInPublicDocumentsFromOtherSpaces() throws Exception {
        Files.writeString(docsDirectory.resolve("public.md"), "# Public\n\nShared knowledge.");
        Path personalDirectory = Files.createDirectories(docsDirectory.resolve("manual-notes/user-1"));
        Files.writeString(personalDirectory.resolve("private.md"), "# Private\n\nPersonal knowledge.");
        service.syncDocsDirectory();

        WorkspaceAccessContext personal = new WorkspaceAccessContext("1", "personal-1", WorkspaceRole.OWNER, WorkspaceType.PERSONAL);

        assertThat(service.listWorkspaceIndexedDocuments(personal))
                .extracting(DocumentIndexEntry::fileName)
                .containsExactly("private.md")
                .doesNotContain("public.md");
    }

    @Test
    void rejectsDeletingAnotherUsersPrivateDocument() throws Exception {
        Path directory = Files.createDirectories(docsDirectory.resolve("manual-notes/user-2"));
        Files.writeString(directory.resolve("private.md"), "# Private\n\nOnly user two may delete this index.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);

        assertThatThrownBy(() -> service.deleteDocument(entry.documentId(), "1", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Indexed document not found");
        assertThat(indexStore.list()).containsExactly(entry);
    }

    @Test
    void onlyAdministratorsCanDeletePublicDocuments() throws Exception {
        Files.writeString(docsDirectory.resolve("public.md"), "# Public\n\nShared knowledge.");
        service.syncDocsDirectory();
        DocumentIndexEntry entry = indexStore.list().get(0);

        assertThatThrownBy(() -> service.deleteDocument(entry.documentId(), "1", false))
                .isInstanceOf(IllegalArgumentException.class);

        service.deleteDocument(entry.documentId(), "admin", true);
        assertThat(indexStore.list()).isEmpty();
    }

    @Test
    void workspaceMembersCanReadButOnlyEditorsCanDeleteWorkspaceDocuments() throws Exception {
        Path document = docsDirectory.resolve("team.md");
        Files.writeString(document, "# Team\n\nWorkspace-only architecture notes.");
        DocumentIndexEntry entry = new DocumentIndexEntry(
                "team-doc", "team.md", "docs/team.md", "team-hash", 1, 100L,
                "SOURCE", "INDEXED", "1", "team-1", DocumentVisibility.WORKSPACE
        );
        indexStore.upsertAll(List.of(entry));
        DocumentIndexEntry personal = new DocumentIndexEntry(
                "personal-doc", "personal.md", "docs/personal.md", "personal-hash", 1, 100L,
                "SOURCE", "INDEXED", "2", "personal-2", DocumentVisibility.PRIVATE
        );
        indexStore.upsertAll(List.of(personal));

        WorkspaceAccessContext viewer = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.VIEWER);
        WorkspaceAccessContext outsider = new WorkspaceAccessContext("3", "team-2", WorkspaceRole.OWNER);
        WorkspaceAccessContext editor = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.EDITOR);

        assertThat(service.listVisibleIndexedDocuments(viewer)).containsExactly(entry);
        assertThat(service.documentContent(entry.documentId(), viewer).content()).contains("Workspace-only");
        assertThatThrownBy(() -> service.documentContent(entry.documentId(), outsider))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteDocument(entry.documentId(), viewer, false))
                .isInstanceOf(IllegalArgumentException.class);

        service.deleteDocument(entry.documentId(), editor, false);
        assertThat(indexStore.list()).containsExactly(personal);
    }

    @Test
    void viewerCannotUploadWorkspaceDocuments() {
        WorkspaceAccessContext viewer = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.VIEWER, WorkspaceType.TEAM);
        MockMultipartFile file = new MockMultipartFile("file", "team.md", "text/markdown", "# Team\n\nPrivate team content.".getBytes());

        assertThatThrownBy(() -> service.uploadWorkspaceDocument(viewer, file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        assertThat(indexStore.list()).isEmpty();
    }

    @Test
    void uploadsTextPdfAndKeepsPageMetadataForRetrieval() throws Exception {
        WorkspaceAccessContext editor = new WorkspaceAccessContext(
                "2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM
        );
        byte[] pdf = PdfTestDocuments.textPdf(
                "Architecture", "First page architecture", "Second page boundaries"
        );

        WorkspaceDocumentUploadResponse response = service.uploadWorkspaceDocument(editor,
                new MockMultipartFile("file", "architecture.pdf", "application/pdf", pdf));

        assertThat(response.fileName()).isEqualTo("architecture.pdf");
        assertThat(service.listDocuments()).extracting(SourceDocument::pageNumber).containsExactly(1, 2);
        assertThat(service.documentContent(response.documentId(), editor).content())
                .contains("First page architecture", "Second page boundaries");
    }

    @Test
    void resumesPdfFromPersistedBatchArtifactsWithoutReprocessingCompletedBatch() throws Exception {
        DocumentTaskBatchArtifactStore artifactStore = Mockito.mock(DocumentTaskBatchArtifactStore.class);
        DocumentChunk cachedChunk = new DocumentChunk("cached first batch", 0, "", 0, 0, 18,
                "pdf-page", 1);
        Mockito.when(artifactStore.load("resume-task")).thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of(
                1, new DocumentTaskBatchArtifactStore.SavedBatch("Resume PDF", List.of(cachedChunk)))));
        DocumentIngestionService resumableService = new DocumentIngestionService(
                vectorStore, indexStore, parserRouter(), new DocumentChunkerRouter(List.of(
                new MarkdownSmartChunker(), new TextParagraphChunker(), new PdfPageChunker(),
                new DocxBlockChunker(), new HtmlBlockChunker())), docsDirectory, 2);
        resumableService.setBatchArtifactStore(artifactStore);
        WorkspaceAccessContext editor = new WorkspaceAccessContext(
                "2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);
        DocumentIngestionService.PendingWorkspaceUpload upload = resumableService.saveWorkspaceUpload(editor,
                new MockMultipartFile("file", "resume.pdf", "application/pdf", PdfTestDocuments.textPdf(
                        "Resume PDF", "page one", "page two", "page three", "page four")));

        WorkspaceDocumentUploadResponse response = resumableService.indexWorkspaceUpload(
                editor, upload.sourcePath(), upload.fileName(), "resume-task", (stage, progress) -> { });

        Mockito.verify(artifactStore, never()).save(Mockito.eq("resume-task"), Mockito.eq(1),
                Mockito.any(), Mockito.anyList());
        Mockito.verify(artifactStore).save(Mockito.eq("resume-task"), Mockito.eq(2),
                Mockito.eq("Resume PDF"), Mockito.anyList());
        assertThat(response.chunks()).isEqualTo(resumableService.listDocuments().size());
        assertThat(resumableService.listDocuments()).extracting(SourceDocument::content)
                .contains("cached first batch", "page three", "page four")
                .doesNotContain("page one", "page two");
    }

    @Test
    void removesPreviouslyWrittenPdfBatchesWhenLaterVectorWriteFails() throws Exception {
        VectorStore failingVectorStore = Mockito.mock(VectorStore.class);
        Mockito.doNothing().doThrow(new IllegalStateException("Failed to write documents to Chroma"))
                .when(failingVectorStore).addAll(Mockito.anyList());
        DocumentTaskBatchArtifactStore artifactStore = Mockito.mock(DocumentTaskBatchArtifactStore.class);
        Mockito.when(artifactStore.load("failed-task")).thenReturn(new java.util.LinkedHashMap<>());
        DocumentIngestionService failingService = new DocumentIngestionService(
                failingVectorStore, indexStore, parserRouter(), new DocumentChunkerRouter(List.of(
                new MarkdownSmartChunker(), new TextParagraphChunker(), new PdfPageChunker(),
                new DocxBlockChunker(), new HtmlBlockChunker())), docsDirectory, 2);
        failingService.setBatchArtifactStore(artifactStore);
        WorkspaceAccessContext editor = new WorkspaceAccessContext(
                "2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);
        DocumentIngestionService.PendingWorkspaceUpload upload = failingService.saveWorkspaceUpload(editor,
                new MockMultipartFile("file", "rollback.pdf", "application/pdf", PdfTestDocuments.textPdf(
                        "Rollback PDF", "page one", "page two", "page three", "page four")));

        assertThatThrownBy(() -> failingService.indexWorkspaceUpload(
                editor, upload.sourcePath(), upload.fileName(), "failed-task", (stage, progress) -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chroma");

        Mockito.verify(failingVectorStore).deleteByIds(Mockito.argThat(ids -> ids.size() >= 2));
        assertThat(indexStore.list()).isEmpty();
        assertThat(failingService.listDocuments()).isEmpty();
    }

    @Test
    void uploadsScannedPdfUsingOcr() throws Exception {
        WorkspaceAccessContext editor = new WorkspaceAccessContext(
                "2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM
        );

        WorkspaceDocumentUploadResponse response = service.uploadWorkspaceDocument(editor,
                new MockMultipartFile("file", "scan.pdf", "application/pdf", PdfTestDocuments.scannedPdf()));

        assertThat(response.fileName()).isEqualTo("scan.pdf");
        assertThat(service.documentContent(response.documentId(), editor).content()).contains("OCR text");
        assertThat(service.listDocuments()).singleElement()
                .satisfies(document -> assertThat(document.pageNumber()).isEqualTo(1));
        Path workspaceDirectory = docsDirectory.resolve("workspaces/team-1");
        try (var files = Files.list(workspaceDirectory)) {
            assertThat(files).singleElement().satisfies(path -> assertThat(path).exists());
        }
    }

    @Test
    void uploadsPreviewsAndDeletesStructuredDocx() throws Exception {
        WorkspaceAccessContext editor = new WorkspaceAccessContext(
                "2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM
        );
        MockMultipartFile file = new MockMultipartFile(
                "file", "architecture.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DocxTestDocuments.structuredDocument()
        );

        WorkspaceDocumentUploadResponse response = service.uploadWorkspaceDocument(editor, file);
        Path source = docsDirectory.resolve(response.path().substring("docs/".length()));

        assertThat(response.fileName()).isEqualTo("architecture.docx");
        assertThat(response.path()).endsWith(".docx");
        assertThat(service.listDocuments()).extracting(SourceDocument::chunkType).contains("docx-section");
        String indexedContent = service.listDocuments().stream()
                .map(SourceDocument::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(indexedContent)
                .contains("Introduction before table", "Component", "Processing", "Parse blocks");
        assertThat(service.documentContent(response.documentId(), editor).content())
                .containsSubsequence("Introduction before table", "Component", "Processing", "Parse blocks");

        service.deleteDocument(response.documentId(), editor, false);

        assertThat(Files.exists(source)).isFalse();
        assertThat(indexStore.list()).isEmpty();
    }

    @Test
    void uploadsPreviewsAndDeletesCleanedHtml() throws Exception {
        WorkspaceAccessContext editor = new WorkspaceAccessContext(
                "2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM
        );
        MockMultipartFile file = new MockMultipartFile(
                "file", "architecture.html", "text/html", """
                <!doctype html><html><head><title>Architecture</title></head><body>
                <script>alert('xss')</script><h1>Pipeline</h1><p>Parse the DOM safely.</p>
                <pre><code>  ingest();</code></pre>
                </body></html>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        WorkspaceDocumentUploadResponse response = service.uploadWorkspaceDocument(editor, file);
        Path source = docsDirectory.resolve(response.path().substring("docs/".length()));
        DocumentContentResponse preview = service.documentContent(response.documentId(), editor);

        assertThat(response.fileName()).isEqualTo("architecture.html");
        assertThat(response.path()).endsWith(".html");
        assertThat(service.listDocuments()).extracting(SourceDocument::chunkType)
                .containsExactly("html-heading", "html-paragraph", "html-code");
        assertThat(preview.content()).contains("Pipeline", "Parse the DOM safely.", "  ingest();")
                .doesNotContain("<script>", "alert('xss')", "<h1>");

        service.deleteDocument(response.documentId(), editor, false);

        assertThat(Files.exists(source)).isFalse();
        assertThat(indexStore.list()).isEmpty();
    }

    @Test
    void uploadsPublicPdfWithGloballyVisibleMetadata() throws Exception {
        WorkspaceAccessContext publicEditor = new WorkspaceAccessContext(
                "1", "public-1", WorkspaceRole.EDITOR, WorkspaceType.PUBLIC
        );

        WorkspaceDocumentUploadResponse response = service.uploadWorkspaceDocument(publicEditor,
                new MockMultipartFile("file", "public-guide.pdf", "application/pdf",
                        PdfTestDocuments.textPdf("Public Guide", "Globally visible PDF knowledge.")));

        assertThat(response.workspaceId()).isEqualTo("public-1");
        assertThat(response.visibility()).isEqualTo(DocumentVisibility.PUBLIC);
        assertThat(indexStore.list()).singleElement().satisfies(entry -> {
            assertThat(entry.workspaceId()).isEqualTo("public-1");
            assertThat(entry.visibility()).isEqualTo(DocumentVisibility.PUBLIC);
        });
        assertThat(service.listDocuments()).allSatisfy(document -> {
            assertThat(document.workspaceId()).isEqualTo("public-1");
            assertThat(document.visibility()).isEqualTo(DocumentVisibility.PUBLIC);
        });
        assertThat(vectorStore.similaritySearch("Globally visible", 5, "2", "personal-2"))
                .extracting(SourceDocument::documentId)
                .contains(response.documentId());
    }

    @Test
    void syncsHtmlAndHtmDocumentsFromDocsDirectory() throws Exception {
        Files.writeString(docsDirectory.resolve("guide.html"), "<h1>HTML Guide</h1><p>First source.</p>");
        Files.writeString(docsDirectory.resolve("legacy.htm"), "<h1>HTM Guide</h1><p>Second source.</p>");

        SyncResult result = service.syncDocsDirectory();

        assertThat(result.addedFiles()).isEqualTo(2);
        assertThat(indexStore.list()).extracting(DocumentIndexEntry::fileName)
                .containsExactlyInAnyOrder("guide.html", "legacy.htm");
        assertThat(service.listDocuments()).extracting(SourceDocument::chunkType).containsOnly(
                "html-heading", "html-paragraph"
        );
    }

    @Test
    void pathImportAndSyncOnlyChangeTheSelectedWorkspace() throws Exception {
        Path sharedSource = docsDirectory.resolve("shared.md");
        Files.writeString(sharedSource, "# Shared\n\nInitial workspace content.");
        WorkspaceAccessContext teamOne = new WorkspaceAccessContext("1", "team-1", WorkspaceRole.OWNER, WorkspaceType.TEAM);
        WorkspaceAccessContext teamTwo = new WorkspaceAccessContext("2", "team-2", WorkspaceRole.OWNER, WorkspaceType.TEAM);

        service.ingestDocument(sharedSource.toString(), false, teamOne);
        service.ingestDocument(sharedSource.toString(), false, teamTwo);

        assertThat(indexStore.list()).extracting(DocumentIndexEntry::workspaceId)
                .containsExactlyInAnyOrder("team-1", "team-2");

        DocumentIndexEntry teamOneEntry = service.listVisibleIndexedDocuments(teamOne).get(0);
        Path teamOneSource = docsDirectory.resolve(teamOneEntry.path().substring("docs/".length()));
        Files.writeString(teamOneSource, "# Shared\n\nUpdated workspace content.");
        SyncResult result = service.syncWorkspace(teamOne);

        assertThat(result.updatedFiles()).isEqualTo(1);
        assertThat(service.listVisibleIndexedDocuments(teamOne)).singleElement()
                .satisfies(entry -> assertThat(entry.contentHash())
                        .isNotEqualTo(service.listVisibleIndexedDocuments(teamTwo).get(0).contentHash()));
        assertThat(service.listVisibleIndexedDocuments(teamTwo)).singleElement()
                .satisfies(entry -> assertThat(entry.workspaceId()).isEqualTo("team-2"));
    }

    @Test
    void viewerCannotMaintainWorkspaceSources() throws Exception {
        Files.writeString(docsDirectory.resolve("shared.md"), "# Shared\n\nWorkspace content.");
        WorkspaceAccessContext viewer = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.VIEWER, WorkspaceType.TEAM);

        assertThatThrownBy(() -> service.ingestDocument(docsDirectory.resolve("shared.md").toString(), false, viewer))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        assertThatThrownBy(() -> service.syncWorkspace(viewer))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void assignsVisibilityFromWorkspaceTypeAndUsesRandomStorageName() throws Exception {
        WorkspaceAccessContext editor = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);
        MockMultipartFile file = new MockMultipartFile("file", "architecture.md", "text/markdown", "# Architecture\n\nTeam boundaries.".getBytes());

        WorkspaceDocumentUploadResponse response = service.uploadWorkspaceDocument(editor, file);

        assertThat(response.visibility()).isEqualTo(DocumentVisibility.WORKSPACE);
        assertThat(response.workspaceId()).isEqualTo("team-1");
        assertThat(response.fileName()).isEqualTo("architecture.md");
        assertThat(response.path()).startsWith("docs/workspaces/team-1/").doesNotEndWith("architecture.md");
        assertThat(service.listVisibleIndexedDocuments(editor)).singleElement()
                .satisfies(entry -> assertThat(entry.ownerUserId()).isEqualTo("2"));
        assertThat(Files.isRegularFile(docsDirectory.resolve(response.path().substring("docs/".length())))).isTrue();
    }

    @Test
    void workspaceSyncAndRebuildPreserveTheOriginalUploadFileName() throws Exception {
        WorkspaceAccessContext editor = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);
        WorkspaceDocumentUploadResponse upload = service.uploadWorkspaceDocument(editor,
                new MockMultipartFile("file", "架构设计.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        DocxTestDocuments.simpleDocument("Workspace architecture content.")));
        Path source = docsDirectory.resolve(upload.path().substring("docs/".length()));

        Files.write(source, DocxTestDocuments.simpleDocument("Updated workspace architecture content."));
        service.syncWorkspace(editor);
        assertThat(service.listVisibleIndexedDocuments(editor)).singleElement()
                .extracting(DocumentIndexEntry::fileName)
                .isEqualTo("架构设计.docx");

        service.rebuildDocuments(editor);
        assertThat(service.listVisibleIndexedDocuments(editor)).singleElement()
                .extracting(DocumentIndexEntry::fileName)
                .isEqualTo("架构设计.docx");
    }

    @Test
    void deletingWorkspaceUploadRemovesSourceAndCannotBeReimportedBySync() throws Exception {
        WorkspaceAccessContext editor = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);
        WorkspaceDocumentUploadResponse response = service.uploadWorkspaceDocument(editor,
                new MockMultipartFile("file", "obsolete.md", "text/markdown", "# Obsolete\n\nRemove this source permanently.".getBytes()));
        Path source = docsDirectory.resolve(response.path().substring("docs/".length()));

        service.deleteDocument(response.documentId(), editor, false);
        SyncResult sync = service.syncDocsDirectory();

        assertThat(Files.exists(source)).isFalse();
        assertThat(indexStore.list()).isEmpty();
        assertThat(sync.addedFiles()).isZero();
        assertThat(vectorStore.similaritySearch("permanently", 5)).isEmpty();
    }

    @Test
    void deletingLegacyWorkspaceIndexDoesNotDeleteManuallyManagedSource() throws Exception {
        Path workspaceDirectory = Files.createDirectories(docsDirectory.resolve("workspaces/team-1"));
        Path source = workspaceDirectory.resolve("managed.md");
        Files.writeString(source, "# Managed\n\nAdministrator-managed source.");
        DocumentIndexEntry entry = new DocumentIndexEntry(
                "managed-doc", "managed.md", "docs/workspaces/team-1/managed.md", "managed-hash", 1, 100L,
                "SOURCE", "INDEXED", "2", "team-1", DocumentVisibility.WORKSPACE);
        indexStore.upsertAll(List.of(entry));
        WorkspaceAccessContext editor = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);

        service.deleteDocument(entry.documentId(), editor, false);

        assertThat(Files.isRegularFile(source)).isTrue();
        assertThat(indexStore.list()).isEmpty();
    }

    @Test
    void rejectsDeletingWorkspaceUploadWhenStoredPathIsASymbolicLinkOutsideDocs() throws Exception {
        Path workspaceDirectory = Files.createDirectories(docsDirectory.resolve("workspaces/team-1"));
        Path outsideSource = tempDir.resolve("outside.md");
        Files.writeString(outsideSource, "# Outside\n\nMust never be deleted.");
        Path link = workspaceDirectory.resolve("123e4567-e89b-12d3-a456-426614174000.md");
        try {
            Files.createSymbolicLink(link, outsideSource);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            return;
        }
        DocumentIndexEntry entry = new DocumentIndexEntry(
                "linked-doc", "linked.md", "docs/workspaces/team-1/" + link.getFileName(), "linked-hash", 1, 100L,
                "SOURCE", "INDEXED", "2", "team-1", DocumentVisibility.WORKSPACE);
        indexStore.upsertAll(List.of(entry));
        WorkspaceAccessContext editor = new WorkspaceAccessContext("2", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM);

        assertThatThrownBy(() -> service.deleteDocument(entry.documentId(), editor, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside its workspace directory");
        assertThat(Files.isRegularFile(outsideSource)).isTrue();
        assertThat(indexStore.list()).containsExactly(entry);
    }

    @Test
    void identicalContentInDifferentWorkspacesUsesDifferentDocumentAndChunkIds() {
        byte[] content = "# Shared Template\n\nThe same bytes in two isolated workspaces.".getBytes();
        WorkspaceDocumentUploadResponse first = service.uploadWorkspaceDocument(
                new WorkspaceAccessContext("1", "team-1", WorkspaceRole.EDITOR, WorkspaceType.TEAM),
                new MockMultipartFile("file", "template.md", "text/markdown", content)
        );
        WorkspaceDocumentUploadResponse second = service.uploadWorkspaceDocument(
                new WorkspaceAccessContext("2", "team-2", WorkspaceRole.EDITOR, WorkspaceType.TEAM),
                new MockMultipartFile("file", "template.md", "text/markdown", content)
        );

        assertThat(first.documentId()).isNotEqualTo(second.documentId());
        assertThat(indexStore.list()).hasSize(2).extracting(DocumentIndexEntry::workspaceId)
                .containsExactlyInAnyOrder("team-1", "team-2");
        assertThat(vectorStore.similaritySearch("same bytes", 10)).extracting(SourceDocument::id)
                .contains(first.documentId() + "#chunk-0", second.documentId() + "#chunk-0");
    }
}
