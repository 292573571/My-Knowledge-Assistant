package com.example.workbench.rag;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class RagQualityAuditService {

    private static final Logger log = LoggerFactory.getLogger(RagQualityAuditService.class);
    private final RagQualityGate qualityGate;
    private final RagQualityAuditRepository auditRepository;
    private final boolean enabled;

    public RagQualityAuditService(
            RagQualityGate qualityGate,
            RagQualityAuditRepository auditRepository,
            @Value("${workbench.rag.async-quality.enabled:true}") boolean enabled
    ) {
        this.qualityGate = qualityGate;
        this.auditRepository = auditRepository;
        this.enabled = enabled;
    }

    @Async
    public void audit(String conversationId, String question, String answer, List<RagSource> sources) {
        if (!enabled || answer == null || answer.isBlank()) {
            return;
        }

        boolean modelSupplement = sources == null || sources.isEmpty();
        String status;
        if (modelSupplement) {
            status = "MODEL_SUPPLEMENT";
        } else if (!qualityGate.isEnabled()) {
            // 未开启 LLM 闸门时不伪造“已验证”结论，仅保留来源数量等基础质量信号。
            status = "BASIC_PASS";
        } else {
            status = qualityGate.approvesAnswer(question, answer, sourceDocuments(sources)) ? "PASS" : "FAIL";
        }
        try {
            auditRepository.save(new RagQualityAuditEntity(
                    conversationId, answer.length(), sources == null ? 0 : sources.size(), status));
        } catch (RuntimeException exception) {
            log.warn("RAG async quality audit could not be recorded errorType={}", exception.getClass().getSimpleName());
        }
    }

    private List<SourceDocument> sourceDocuments(List<RagSource> sources) {
        return sources.stream()
                .map(source -> new SourceDocument(
                        source.path(),
                        source.snippet(),
                        source.headingPath(),
                        source.path(),
                        source.file(),
                        source.chunkIndex()
                ))
                .toList();
    }
}
