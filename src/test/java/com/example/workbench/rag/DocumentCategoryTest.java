package com.example.workbench.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentCategoryTest {

    @Test
    void mapsLearningRecordPathPrefixToLearningRecordCategory() {
        assertThat(DocumentCategory.of("docs/learning-records/user-1/2026-07-29.md"))
                .isEqualTo(DocumentCategory.LEARNING_RECORD);
    }

    @Test
    void mapsManualNotePathPrefixToFormalNoteCategory() {
        assertThat(DocumentCategory.of("docs/manual-notes/user-1/2026-07-29-learning-note.md"))
                .isEqualTo(DocumentCategory.FORMAL_NOTE);
    }

    @Test
    void mapsAnyOtherPathToSourceCategory() {
        assertThat(DocumentCategory.of("docs/architecture/rag-quality-loop.md")).isEqualTo(DocumentCategory.SOURCE);
        assertThat(DocumentCategory.of("docs/workspaces/team-1/spec.pdf")).isEqualTo(DocumentCategory.SOURCE);
    }

    @Test
    void normalizesWindowsSeparatorBeforePrefixMatching() {
        assertThat(DocumentCategory.of("docs\\learning-records\\user-1\\2026-07-29.md"))
                .isEqualTo(DocumentCategory.LEARNING_RECORD);
    }

    @Test
    void treatsNullPathAsSourceInsteadOfThrowing() {
        assertThat(DocumentCategory.of(null)).isEqualTo(DocumentCategory.SOURCE);
        assertThat(DocumentCategory.isLearningRecord("SOURCE", null)).isFalse();
        assertThat(DocumentCategory.isPromotedLearningNote("FORMAL_NOTE", null)).isFalse();
    }

    @Test
    void detectsLearningRecordByCategoryOrByPath() {
        assertThat(DocumentCategory.isLearningRecord("LEARNING_RECORD", "docs/anything.md")).isTrue();
        assertThat(DocumentCategory.isLearningRecord("SOURCE", "docs/learning-records/user-2/x.md")).isTrue();
        assertThat(DocumentCategory.isLearningRecord("SOURCE", "docs/workspaces/team-1/x.md")).isFalse();
        assertThat(DocumentCategory.isLearningRecord(null, "docs/workspaces/team-1/x.md")).isFalse();
    }

    @Test
    void detectsPromotedLearningNoteOnlyWhenCategoryAndFileNameBothMatch() {
        assertThat(DocumentCategory.isPromotedLearningNote(
                "FORMAL_NOTE", "docs/manual-notes/user-1/2026-07-29-learning-note.md")).isTrue();
        assertThat(DocumentCategory.isPromotedLearningNote(
                "SOURCE", "docs/manual-notes/user-1/2026-07-29-learning-note.md")).isFalse();
        assertThat(DocumentCategory.isPromotedLearningNote(
                "FORMAL_NOTE", "docs/manual-notes/user-1/2026-07-29-notes.md")).isFalse();
    }

    @Test
    void excludesBothLearningRecordAndPromotedNoteFromKnowledgeBase() {
        assertThat(DocumentCategory.isExcludedFromKnowledgeBase(
                "LEARNING_RECORD", "docs/learning-records/user-1/2026-07-29.md")).isTrue();
        assertThat(DocumentCategory.isExcludedFromKnowledgeBase(
                "FORMAL_NOTE", "docs/manual-notes/user-1/2026-07-29-learning-note.md")).isTrue();
        assertThat(DocumentCategory.isExcludedFromKnowledgeBase(
                "SOURCE", "docs/workspaces/team-1/spec.pdf")).isFalse();
    }
}
