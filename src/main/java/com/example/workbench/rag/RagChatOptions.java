package com.example.workbench.rag;

/** Per-request retrieval switches. They never mutate the service-wide configuration. */
public record RagChatOptions(boolean queryRewriteEnabled, boolean multiQueryEnabled) {
}
