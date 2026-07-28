package com.example.workbench.rag;

import java.util.List;
import reactor.core.publisher.Flux;

public record RagStreamResponse(Flux<String> tokens, List<RagSource> sources) {
}
