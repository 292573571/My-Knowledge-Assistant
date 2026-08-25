package com.example.workbench.agent;

import com.example.workbench.learning.LearningRecordService;
import com.example.workbench.learning.LearningRecordSummary;
import com.example.workbench.rag.RagService;
import com.example.workbench.rag.RagSource;
import com.example.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TeachingReadOnlyService {

    private final RagService ragService;
    private final LearningRecordService learningRecordService;
    private final WorkspaceService workspaceService;

    public TeachingReadOnlyService(RagService ragService, LearningRecordService learningRecordService,
                                   WorkspaceService workspaceService) {
        this.ragService = ragService;
        this.learningRecordService = learningRecordService;
        this.workspaceService = workspaceService;
    }

    public KnowledgeSearchResult search(TeachingAgentContext context, String query, int limit) {
        workspaceService.access(context.user(), context.access().workspaceId());
        int safeLimit = Math.max(1, Math.min(10, limit));
        Set<String> readable = workspaceService.effectiveReadableWorkspaceIds(context.user(), context.access().workspaceId());
        List<RagSource> sources = ragService.retrieveForAgent(query, context.access().userId(), readable, safeLimit);
        return new KnowledgeSearchResult(query.strip(), sources);
    }

    public LearningHistorySummary recentLearningRecords(TeachingAgentContext context, int limit) {
        workspaceService.access(context.user(), context.access().workspaceId());
        int safeLimit = Math.max(1, Math.min(20, limit));
        return new LearningHistorySummary(learningRecordService.list(context.user(), context.access().workspaceId()).stream()
                .limit(safeLimit)
                .toList());
    }

    public record KnowledgeSearchResult(String query, List<RagSource> sources) {
    }

    public record LearningHistorySummary(List<LearningRecordSummary> records) {
    }
}
