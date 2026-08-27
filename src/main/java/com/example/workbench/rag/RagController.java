package com.example.workbench.rag;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.UserConversationScope;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import com.example.workbench.conversation.ConversationService;
import com.example.workbench.audit.AuditAction;
import com.example.workbench.audit.AuditOutcome;
import com.example.workbench.audit.AuditService;
import com.example.workbench.config.HttpRequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import com.example.workbench.config.AiConfig;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import com.example.workbench.pagination.PageResponse;
import java.util.Set;
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
    private final DocumentTaskService documentTaskService;
    private final ConversationService conversationService;

    public RagController(
            DocumentIngestionService documentIngestionService,
            RagService ragService,
            AiConfig aiConfig,
            ObjectProvider<ChatClient> chatClientProvider,
            ChromaVectorStoreAdapter vectorStore,
            AdminAuthorizationService adminAuthorizationService,
            WorkspaceService workspaceService,
            AuditService auditService,
            DocumentTaskService documentTaskService,
            ConversationService conversationService
    ) {
        this.documentIngestionService = documentIngestionService;
        this.ragService = ragService;
        this.aiConfig = aiConfig;
        this.chatClientProvider = chatClientProvider;
        this.vectorStore = vectorStore;
        this.adminAuthorizationService = adminAuthorizationService;
        this.workspaceService = workspaceService;
        this.auditService = auditService;
        this.documentTaskService = documentTaskService;
        this.conversationService = conversationService;
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(HttpServletRequest request) throws IOException {
        requireAdmin(request);
        IngestResult result = documentIngestionService.ingestDocsDirectory();
        return new IngestResponse(result);
    }

    @PostMapping("/documents/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentTaskResponse ingestDocument(@RequestBody(required = false) IngestDocumentRequest request,
                                               @RequestParam(required = false) String workspaceId,
                                               HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        WorkspaceAccessContext workspace = access(httpRequest, workspaceId);
        if (request == null || request.path() == null || request.path().isBlank()) {
            return documentTaskService.createMaintenance(workspace, DocumentTaskType.INGEST_DIRECTORY, null);
        }

        return documentTaskService.createMaintenance(workspace, DocumentTaskType.INGEST_FILE, request.path());
    }

    @PostMapping("/documents/ingest-directory")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentTaskResponse ingestDirectory(@RequestBody(required = false) IngestDirectoryRequest request,
                                                @RequestParam(required = false) String workspaceId,
                                                HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return documentTaskService.createMaintenance(access(httpRequest, workspaceId),
                DocumentTaskType.INGEST_DIRECTORY, request == null ? null : request.path());
    }

    @PostMapping("/documents/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentTaskResponse rebuildDocuments(@RequestParam(required = false) String workspaceId,
                                                 HttpServletRequest request) {
        requireAdmin(request);
        return documentTaskService.createMaintenance(access(request, workspaceId), DocumentTaskType.REBUILD, null);
    }

    @PostMapping("/documents/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentTaskResponse syncDocuments(@RequestParam(required = false) String workspaceId,
                                              HttpServletRequest request) {
        requireAdmin(request);
        return documentTaskService.createMaintenance(access(request, workspaceId), DocumentTaskType.SYNC, null);
    }

    @GetMapping("/documents")
    public Object documents(@RequestParam(required = false) String workspaceId,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size,
                            HttpServletRequest request) {
        List<DocumentIndexEntry> documents = documentIngestionService.listWorkspaceIndexedDocuments(access(request, workspaceId));
        return page == null && size == null ? documents : PageResponse.of(documents, page, size);
    }

    @PostMapping(path = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentTaskResponse uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam String clientRequestId,
            @RequestParam(required = false) String workspaceId,
            HttpServletRequest request
    ) {
        AppUser actor = authenticatedUser(request);
        String targetWorkspace = workspaceId == null || workspaceId.isBlank() ? "personal-" + actor.getId() : workspaceId;
        try {
            DocumentTaskResponse result = documentTaskService.createUpload(
                    workspaceService.access(actor, workspaceId), file, clientRequestId);
            record(actor, result.workspaceId(), AuditAction.DOCUMENT_UPLOAD, "DOCUMENT_TASK", result.taskId(),
                    AuditOutcome.SUCCESS, "NONE", requestId(request));
            return result;
        } catch (RuntimeException exception) {
            record(actor, targetWorkspace, AuditAction.DOCUMENT_UPLOAD, "DOCUMENT", "pending",
                    auditService.outcome(exception), auditService.reasonCode(exception), requestId(request));
            throw exception;
        }
    }

    @GetMapping("/document-tasks")
    public Object documentTasks(@RequestParam(required = false) String workspaceId,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    HttpServletRequest request) {
        AppUser actor = authenticatedUser(request);
        WorkspaceAccessContext access = workspaceService.access(actor, workspaceId);
        return page == null && size == null
                ? documentTaskService.list(access, adminAuthorizationService.isAdmin(actor))
                : documentTaskService.page(access, adminAuthorizationService.isAdmin(actor), page, size);
    }

    @GetMapping("/document-tasks/{taskId}/batches")
    public List<DocumentTaskBatchResponse> documentTaskBatches(
            @PathVariable String taskId,
            @RequestParam(required = false) String workspaceId,
            HttpServletRequest request
    ) {
        AppUser actor = authenticatedUser(request);
        return documentTaskService.batches(taskId, workspaceService.access(actor, workspaceId),
                adminAuthorizationService.isAdmin(actor));
    }

    @PostMapping("/document-tasks/{taskId}/retry")
    public DocumentTaskResponse retryDocumentTask(@PathVariable String taskId,
                                                  @RequestParam(required = false) String workspaceId,
                                                  HttpServletRequest request) {
        AppUser actor = authenticatedUser(request);
        return documentTaskService.retry(taskId, workspaceService.access(actor, workspaceId),
                adminAuthorizationService.isAdmin(actor));
    }

    @GetMapping("/document-tasks/{taskId}/source")
    public org.springframework.http.ResponseEntity<byte[]> documentTaskSource(
            @PathVariable String taskId,
            @RequestParam(required = false) String workspaceId,
            HttpServletRequest request
    ) {
        AppUser actor = authenticatedUser(request);
        DocumentSourceFile source = documentTaskService.sourceFile(
                taskId, workspaceService.access(actor, workspaceId));
        org.springframework.http.MediaType mediaType = org.springframework.http.MediaTypeFactory
                .getMediaType(source.fileName())
                .orElse(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(source.content().length)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline()
                                .filename(source.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .body(source.content());
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
        String clientConversationId = request.normalizedConversationId();
        String mode = "rag";
        conversationService.recordUserMessage(user, workspace.workspaceId(), clientConversationId,
                request.message().strip().substring(0, Math.min(request.message().strip().length(), 24)), mode,
                request.message());
        RagChatResponse response = ragService.chat(user, new RagChatRequest(
                UserConversationScope.id(user, request.normalizedConversationId()), workspace.workspaceId(),
                clientConversationId, request.message()));
        conversationService.recordAssistantMessage(user, workspace.workspaceId(), clientConversationId,
                mode, response.answer(), response.sources(), List.of());
        return response;
    }

    @PostMapping("/rag/debug")
    public RetrievalDebugResponse debugRetrieval(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        AppUser user = authenticatedUser(httpRequest);
        WorkspaceAccessContext workspace = workspaceService.access(user, body == null ? null : body.get("workspaceId"));
        Set<String> readable = workspaceService.effectiveReadableWorkspaceIds(user, workspace.workspaceId());
        return ragService.debugRetrieval(body == null ? null : body.get("message"), workspace.userId(), readable);
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
