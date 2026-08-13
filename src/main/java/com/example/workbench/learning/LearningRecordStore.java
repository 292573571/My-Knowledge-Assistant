package com.example.workbench.learning;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.RagSource;
import java.time.LocalDate;
import java.util.List;

interface LearningRecordStore {
    String recordChat(AppUser user, String workspaceId, LocalDate date, String question, String answer,
                      List<RagSource> sources, String markdown);

    String recordTeachingExplanation(AppUser user, String workspaceId, LocalDate date, String sessionId,
                                     String topic, String explanation, List<RagSource> sources, String markdown);

    String recordTeachingCheck(AppUser user, String workspaceId, LocalDate date, String attemptId,
                               String topic, String question, String answer, int score, int maxScore,
                               boolean passed, String feedback, String weakPoint, String reviewExplanation,
                               String reviewSuggestion, String markdown);

    String recordTeachingPractice(AppUser user, String workspaceId, LocalDate date, String checkId,
                                  String practiceId, String topic, String question, String answer,
                                  int score, int maxScore, boolean passed, String feedback, String markdown);

    List<LearningRecordEntry> visible(AppUser user, String workspaceId, boolean includeLegacy);

    List<LearningRecordEntry> visibleOnDate(AppUser user, String workspaceId, LocalDate date, boolean includeLegacy);

    void replaceOnDate(AppUser user, String workspaceId, LocalDate date, List<LearningRecordEntry> entries,
                       boolean includeLegacy);

    void deleteOnDate(AppUser user, String workspaceId, LocalDate date, boolean includeLegacy);

    void saveFormalNote(AppUser user, String workspaceId, LocalDate date, String fileName, String path, String content);
}
