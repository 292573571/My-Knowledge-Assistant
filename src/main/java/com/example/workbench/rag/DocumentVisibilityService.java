package com.example.workbench.rag;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.workspace.DocumentVisibility;
import com.example.workbench.workspace.WorkspaceAccessContext;
import com.example.workbench.workspace.WorkspaceService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档可见性变更。
 *
 * <p>可见性同时存在于 PostgreSQL（document_indexes / document_chunks）与 Chroma 元数据中，
 * 两处必须一起改：只改数据库，向量召回仍会按旧元数据放行；只改向量库，稀疏检索仍会命中。</p>
 */
@Service
public class DocumentVisibilityService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVisibilityService.class);

    private final WorkspaceService workspaceService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final DocumentIndexRepository indexRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ChromaVectorStoreAdapter vectorStore;

    public DocumentVisibilityService(WorkspaceService workspaceService,
                                     AdminAuthorizationService adminAuthorizationService,
                                     DocumentIndexRepository indexRepository,
                                     DocumentChunkRepository chunkRepository,
                                     ChromaVectorStoreAdapter vectorStore) {
        this.workspaceService = workspaceService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.indexRepository = indexRepository;
        this.chunkRepository = chunkRepository;
        this.vectorStore = vectorStore;
    }

    @Transactional
    public DocumentIndexEntry change(AppUser user, String documentId, String workspaceId,
                                     DocumentVisibility target) {
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "可见性不能为空");
        }
        WorkspaceAccessContext access = workspaceService.access(user, workspaceId);
        DocumentIndexEntity entity = indexRepository
                .findByDocumentIdAndWorkspaceId(documentId, access.workspaceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或不属于当前空间"));
        DocumentIndexEntry current = entity.toEntry();
        if (current.visibility() == target) {
            return current;
        }
        assertAllowed(user, access, current, target);

        List<DocumentChunkEntity> chunks = chunkRepository
                .findByDocumentIdAndWorkspaceIdOrderByChunkIndex(documentId, access.workspaceId());
        entity.changeVisibility(target);
        chunks.forEach(chunk -> chunk.changeVisibility(target));
        indexRepository.save(entity);
        chunkRepository.saveAll(chunks);

        vectorStore.refreshVisibilityMetadata(chunks.stream()
                .map(chunk -> chunk.toSourceDocument(0.0))
                .toList());

        log.info("Document visibility changed documentId={} workspaceId={} from={} to={}",
                documentId, access.workspaceId(), current.visibility(), target);
        return entity.toEntry();
    }

    private void assertAllowed(AppUser user, WorkspaceAccessContext access, DocumentIndexEntry current,
                               DocumentVisibility target) {
        boolean owner = !current.ownerUserId().isBlank() && current.ownerUserId().equals(access.userId());
        boolean writer = access.canWrite();
        boolean allowed = switch (target) {
            case PRIVATE -> owner || writer;
            case WORKSPACE -> writer;
            case PUBLIC -> writer && adminAuthorizationService.isAdmin(user);
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有修改该文档可见性的权限");
        }
    }
}
