package com.example.workbench.rag;

import java.util.List;

public interface DocumentChunker {

    boolean supports(String fileName);

    List<DocumentChunk> chunk(String content);
}
