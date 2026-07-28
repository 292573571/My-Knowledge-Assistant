package com.example.workbench.rag;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.UserConversationScope;
import jakarta.servlet.http.HttpServletRequest;
import com.example.workbench.config.AiConfig;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api")
public class RagController {

    private final DocumentIngestionService documentIngestionService;
    private final RagService ragService;
    private final AiConfig aiConfig;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ChromaVectorStoreAdapter vectorStore;

    public RagController(
            DocumentIngestionService documentIngestionService,
            RagService ragService,
            AiConfig aiConfig,
            ObjectProvider<ChatClient> chatClientProvider,
            ChromaVectorStoreAdapter vectorStore
    ) {
        this.documentIngestionService = documentIngestionService;
        this.ragService = ragService;
        this.aiConfig = aiConfig;
        this.chatClientProvider = chatClientProvider;
        this.vectorStore = vectorStore;
    }

    @PostMapping("/ingest")
    public IngestResponse ingest() throws IOException {
        IngestResult result = documentIngestionService.ingestDocsDirectory();
        return new IngestResponse(result);
    }

    @PostMapping("/documents/ingest")
    public IngestResponse ingestDocument(@RequestBody(required = false) IngestDocumentRequest request) throws IOException {
        if (request == null || request.path() == null || request.path().isBlank()) {
            return documentIngestionService.ingestDirectory(null, false);
        }

        return documentIngestionService.ingestDocument(request.path(), request.force());
    }

    @PostMapping("/documents/ingest-directory")
    public IngestResponse ingestDirectory(@RequestBody(required = false) IngestDirectoryRequest request) throws IOException {
        if (request == null) {
            return documentIngestionService.ingestDirectory(null, false);
        }

        return documentIngestionService.ingestDirectory(request.path(), request.force());
    }

    @PostMapping("/documents/rebuild")
    public RebuildResult rebuildDocuments() throws IOException {
        return documentIngestionService.rebuildDocuments();
    }

    @PostMapping("/documents/sync")
    public SyncResult syncDocuments() throws IOException {
        return documentIngestionService.syncDocsDirectory();
    }

    @GetMapping("/documents")
    public List<DocumentIndexEntry> documents() {
        return documentIngestionService.listIndexedDocuments();
    }

    @GetMapping("/documents/{documentId}/content")
    public DocumentContentResponse documentContent(@PathVariable String documentId, HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        String ownerUserId = user.getId() == null ? user.getAccount() : user.getId().toString();
        return documentIngestionService.documentContent(documentId, ownerUserId);
    }

    @GetMapping("/health")
    public WorkbenchStatus health() {
        List<DocumentIndexEntry> documents = documentIngestionService.listIndexedDocuments();
        boolean chromaConfigured = vectorStore.isChromaConfigured();

        return new WorkbenchStatus(
                aiConfig.provider(),
                aiConfig.model(),
                chatClientProvider.getIfAvailable() != null,
                chromaConfigured ? "Chroma" : "内存回退",
                chromaConfigured,
                documents.size(),
                documents.stream().mapToInt(DocumentIndexEntry::chunkCount).sum()
        );
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String documentId) {
        documentIngestionService.deleteDocument(documentId);
    }

    @PostMapping("/rag/chat")
    public RagChatResponse chat(@Valid @RequestBody RagChatRequest request, HttpServletRequest httpRequest) {
        AppUser user = (AppUser) httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        return ragService.chat(new RagChatRequest(UserConversationScope.id(user, request.normalizedConversationId()), request.message()));
    }
}
