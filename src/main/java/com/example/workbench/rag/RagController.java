package com.example.workbench.rag;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.audit.AuditAction;
import com.example.workbench.audit.AuditOutcome;
import com.example.workbench.audit.AuditService;
import com.example.workbench.config.HttpRequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import com.example.workbench.config.AiConfig;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import java.util.Map;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);
    private final DocumentIngestionService documentIngestionService;
    private final RagService ragService;
    private final AiConfig aiConfig;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final ChromaVectorStoreAdapter vectorStore;
    private final AdminAuthorizationService adminAuthorizationService;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    public RagController(
            DocumentIngestionService documentIngestionService,
            RagService ragService,
            AiConfig aiConfig,
            ObjectProvider<ChatClient> chatClientProvider,
            ChromaVectorStoreAdapter vectorStore,
            AdminAuthorizationService adminAuthorizationService,
            WorkspaceService workspaceService,
            AuditService auditService
    ) {
        this.documentIngestionService = documentIngestionService;
        this.ragService = ragService;
        this.aiConfig = aiConfig;
        this.chatClientProvider = chatClientProvider;
        this.vectorStore = vectorStore;
        this.adminAuthorizationService = adminAuthorizationService;
        this.workspaceService = workspaceService;
        this.auditService = auditService;
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(HttpServletRequest request) throws IOException {
        requireAdmin(request);
        IngestResult result = documentIngestionService.ingestDocsDirectory();
        return new IngestResponse(result);
    }

    @PostMapping("/documents/ingest")
    public IngestResponse ingestDocument(@RequestBody(required = false) IngestDocumentRequest request,
                                         @RequestParam(required = false) String workspaceId,
                                         HttpServletRequest httpRequest) throws IOException {
        requireAdmin(httpRequest);
        if (request == null || request.path() == null || request.path().isBlank()) {
            return documentIngestionService.ingestDirectory(null, false, access(httpRequest, workspaceId));
        }

        return documentIngestionService.ingestDocument(request.path(), request.force(), access(httpRequest, workspaceId));
    }

    @PostMapping("/documents/ingest-directory")
    public IngestResponse ingestDirectory(@RequestBody(required = false) IngestDirectoryRequest request,
                                          @RequestParam(required = false) String workspaceId,
                                          HttpServletRequest httpRequest) throws IOException {
        requireAdmin(httpRequest);
        if (request == null) {
            return documentIngestionService.ingestDirectory(null, false, access(httpRequest, workspaceId));
        }

        return documentIngestionService.ingestDirectory(request.path(), request.force(), access(httpRequest, workspaceId));
    }

    @PostMapping("/documents/rebuild")
    public RebuildResult rebuildDocuments(@RequestParam(required = false) String workspaceId,
                                          HttpServletRequest request) throws IOException {
        requireAdmin(request);
        return documentIngestionService.rebuildDocuments(access(request, workspaceId));
    }

    @PostMapping("/documents/sync")
    public SyncResult syncDocuments(@RequestParam(required = false) String workspaceId,
                                    HttpServletRequest request) throws IOException {
        requireAdmin(request);
        return documentIngestionService.syncWorkspace(access(request, workspaceId));
    }

    @GetMapping("/documents")
    public List<DocumentIndexEntry> documents(@RequestParam(required = false) String workspaceId, HttpServletRequest request) {
        return documentIngestionService.listWorkspaceIndexedDocuments(access(request, workspaceId));
    }

    @PostMapping(path = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceDocumentUploadResponse uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String workspaceId,
            HttpServletRequest request
    ) {
        AppUser actor = authenticatedUser(request);
        String targetWorkspace = workspaceId == null || workspaceId.isBlank() ? "personal-" + actor.getId() : workspaceId;
        try {
            WorkspaceDocumentUploadResponse result = documentIngestionService.uploadWorkspaceDocument(
                    workspaceService.access(actor, workspaceId), file);
            record(actor, result.workspaceId(), AuditAction.DOCUMENT_UPLOAD, "DOCUMENT", result.documentId(),
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
            return result;
        } catch (RuntimeException exception) {
            record(actor, targetWorkspace, AuditAction.DOCUMENT_UPLOAD, "DOCUMENT", "pending",
                    auditService.outcome(exception), auditService.reasonCode(exception), requestId(request));
            throw exception;
        }
    }

    @GetMapping("/documents/{documentId}/content")
    public DocumentContentResponse documentContent(@PathVariable String documentId,
                                                   @RequestParam(required = false) String workspaceId,
                                                   HttpServletRequest request) {
        return documentIngestionService.documentContent(documentId, access(request, workspaceId));
    }

    @GetMapping("/health")
    public WorkbenchStatus health() {
        List<DocumentIndexEntry> documents = documentIngestionService.listPublicIndexedDocuments();
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
    public void deleteDocument(@PathVariable String documentId,
                               @RequestParam(required = false) String workspaceId,
                               HttpServletRequest request) {
        AppUser user = authenticatedUser(request);
        String targetWorkspace = workspaceId == null || workspaceId.isBlank() ? "personal-" + user.getId() : workspaceId;
        try {
            documentIngestionService.deleteDocument(documentId, workspaceService.access(user, workspaceId), adminAuthorizationService.isAdmin(user));
            record(user, targetWorkspace, AuditAction.DOCUMENT_DELETE, "DOCUMENT", documentId,
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
        } catch (RuntimeException exception) {
            record(user, targetWorkspace, AuditAction.DOCUMENT_DELETE, "DOCUMENT", documentId,
                    auditService.outcome(exception), auditService.reasonCode(exception), requestId(request));
            throw exception;
        }
    }

    @PostMapping("/rag/chat")
    public RagChatResponse chat(@Valid @RequestBody RagChatRequest request, HttpServletRequest httpRequest) {
        AppUser user = authenticatedUser(httpRequest);
        WorkspaceAccessContext workspace = workspaceService.access(user, request.workspaceId());
        return ragService.chat(new RagChatRequest(UserConversationScope.id(user, request.normalizedConversationId()), workspace.workspaceId(), request.message()));
    }

    @PostMapping("/rag/debug")
    public RetrievalDebugResponse debugRetrieval(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        AppUser user = authenticatedUser(httpRequest);
        WorkspaceAccessContext workspace = workspaceService.access(user, body == null ? null : body.get("workspaceId"));
        return ragService.debugRetrieval(body == null ? null : body.get("message"), workspace.userId(), workspace.workspaceId());
    }

    private AppUser authenticatedUser(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }

    private void requireAdmin(HttpServletRequest request) {
        adminAuthorizationService.requireAdmin(authenticatedUser(request));
    }

    private WorkspaceAccessContext access(HttpServletRequest request, String workspaceId) {
        return workspaceService.access(authenticatedUser(request), workspaceId);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(HttpRequestLoggingFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    private void record(AppUser actor, String workspaceId, AuditAction action, String resourceType,
                        String resourceId, AuditOutcome outcome, String reasonCode, String requestId) {
        try {
            auditService.record(actor, workspaceId, action, resourceType, resourceId, outcome, reasonCode, requestId);
        } catch (RuntimeException auditException) {
            log.error("Business audit persistence failed action={} outcome={}", action, outcome);
        }
    }
}
