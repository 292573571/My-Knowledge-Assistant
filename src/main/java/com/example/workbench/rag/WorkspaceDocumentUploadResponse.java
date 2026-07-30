package com.example.workbench.rag;

import com.example.workbench.workspace.DocumentVisibility;

public record WorkspaceDocumentUploadResponse(
        String documentId,
        String fileName,
        String path,
        int chunks,
        String workspaceId,
        DocumentVisibility visibility
) {
}
