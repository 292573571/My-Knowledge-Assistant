package com.example.workbench.agent;

import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.learning.LearningRecordSummary;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagSource;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TeachingReadOnlyService {

    private final RagService ragService;
    private final LearningRecordService learningRecordService;

    public TeachingReadOnlyService(RagService ragService, LearningRecordService learningRecordService) {
        this.ragService = ragService;
        this.learningRecordService = learningRecordService;
    }

    public KnowledgeSearchResult search(TeachingAgentContext context, String query, int limit) {
        int safeLimit = Math.max(1, Math.min(10, limit));
        List<RagSource> sources = ragService.retrieveForAgent(query, context.access().userId(),
                context.access().workspaceId(), safeLimit);
        return new KnowledgeSearchResult(query.strip(), sources);
    }

    public LearningHistorySummary recentLearningRecords(TeachingAgentContext context, int limit) {
        int safeLimit = Math.max(1, Math.min(20, limit));
        return new LearningHistorySummary(learningRecordService.list(context.user()).stream()
                .limit(safeLimit)
                .toList());
    }

    public record KnowledgeSearchResult(String query, List<RagSource> sources) {
    }

    public record LearningHistorySummary(List<LearningRecordSummary> records) {
    }
}
