package com.example.workbench.rag;

import java.util.List;

public interface DocumentChunker {

    boolean supports(ParsedDocument document);

    List<DocumentChunk> chunk(ParsedDocument document);
}
