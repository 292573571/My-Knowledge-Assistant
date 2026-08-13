package com.example.workbench.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.workbench.auth.AppUser;
import com.example.workbench.rag.DocumentIngestionService;
import com.example.workbench.rag.RagSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class LearningRecordServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsTeachingReviewOnceForTheSameAttempt() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(
                ingestionService, clock, tempDir.resolve("docs/learning-records"));
        AppUser user = new AppUser("alice", "Alice", "hash");

        service.recordTeachingCheck(user, "attempt-1", "Agent", "Agent 为什么需要工具？",
                "Agent 会回答问题。", 2, 5, false, "需要复习", "没有说明工具调用",
                "工具结果会回到模型上下文。", "请用例子重新说明。");
        service.recordTeachingCheck(user, "attempt-1", "Agent", "Agent 为什么需要工具？",
                "另一个答案", 2, 5, false, "需要复习", "另一个薄弱点", "另一个解释", "另一个建议");

        String content = Files.readString(tempDir.resolve("docs/learning-records/user-alice/2026-07-26.md"));
        assertThat(content)
                .contains("## 教学检查")
                .contains("### 针对性复习")
                .contains("没有说明工具调用")
                .contains("工具结果会回到模型上下文")
                .containsOnlyOnce("attemptId：attempt-1")
                .doesNotContain("另一个答案");
        verify(ingestionService, never()).ingestDocument(Mockito.anyString(), eq(true));
    }

    @Test
    void summarizesTeachingProgressByTopicFromPersistedChecks() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        Path records = tempDir.resolve("docs/learning-records");
        LearningRecordService service = new LearningRecordService(ingestionService, clock, records);
        AppUser user = new AppUser("alice", "Alice", "hash");

        service.recordTeachingCheck(user, "attempt-1", "Agent", "问题一", "答案一", 2, 5,
                false, "需要复习", "薄弱点", "解释", "建议");
        service.recordTeachingCheck(user, "attempt-2", "Agent", "问题二", "答案二", 5, 5,
                true, "通过", null, null, null);
        service.recordTeachingCheck(user, "attempt-3", "RAG", "问题三", "答案三", 4, 5,
                true, "通过", null, null, null);

        List<TeachingTopicProgress> progress = service.teachingProgress(user);

        assertThat(progress).extracting(TeachingTopicProgress::topic)
                .containsExactly("Agent", "RAG");
        assertThat(progress.get(0).attempts()).isEqualTo(2);
        assertThat(progress.get(0).passedAttempts()).isEqualTo(1);
        assertThat(progress.get(0).bestScore()).isEqualTo(5);
        assertThat(progress.get(0).latestScore()).isEqualTo(5);
        assertThat(progress.get(0).latestPassed()).isTrue();
        assertThat(progress.get(0).masteryPercent()).isEqualTo(100);
    }

    @Test
    void mergesTopicProgressThatOnlyDiffersByCaseOrWhitespace() {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock,
                tempDir.resolve("docs/learning-records"));
        AppUser user = new AppUser("alice", "Alice", "hash");

        service.recordTeachingCheck(user, "attempt-1", " Agent ", "问题一", "答案一", 2, 5,
                false, "需要复习", "薄弱点", "解释", "建议");
        service.recordTeachingCheck(user, "attempt-2", "agent", "问题二", "答案二", 5, 5,
                true, "通过", null, null, null);

        List<TeachingTopicProgress> progress = service.teachingProgress(user);

        assertThat(progress).hasSize(1);
        assertThat(progress.get(0).topic()).isEqualTo("agent");
        assertThat(progress.get(0).attempts()).isEqualTo(2);
        assertThat(progress.get(0).latestPassed()).isTrue();
    }

    @Test
    void filtersTeachingProgressByWorkspace() {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock,
                tempDir.resolve("docs/learning-records"));
        AppUser user = new AppUser("alice", "Alice", "hash");

        service.recordTeachingCheck(user, "workspace-a", "attempt-a", "Agent", "问题", "答案", 5, 5,
                true, "通过", null, null, null);
        service.recordTeachingCheck(user, "workspace-b", "attempt-b", "Agent", "问题", "答案", 1, 5,
                false, "需要复习", "薄弱点", "解释", "建议");

        assertThat(service.teachingProgress(user, "workspace-a"))
                .extracting(TeachingTopicProgress::latestScore)
                .containsExactly(5);
        assertThat(service.teachingProgress(user, "workspace-b"))
                .extracting(TeachingTopicProgress::latestScore)
                .containsExactly(1);
    }

    @Test
    void isolatesLearningRecordDetailAndUpdatesByWorkspace() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        Path records = tempDir.resolve("docs/learning-records");
        LearningRecordService service = new LearningRecordService(ingestionService, clock, records);
        AppUser user = new AppUser("alice", "Alice", "hash");

        service.record(user, "workspace-a", "A 空间问题", "A 空间回答内容。", List.of());
        service.record(user, "workspace-b", "B 空间问题", "B 空间回答内容。", List.of());

        assertThat(service.detail(user, "workspace-a", "2026-07-26").content())
                .contains("A 空间问题")
                .doesNotContain("B 空间问题");
        assertThat(service.detail(user, "workspace-b", "2026-07-26").content())
                .contains("B 空间问题")
                .doesNotContain("A 空间问题");

        service.update(user, "workspace-a", "2026-07-26",
                "# 2026-07-26 学习记录\n\n## 问题\n\n- 知识空间：workspace-b\n\nA 空间更新问题\n\n## 回答\n\nA 空间更新回答。");

        String stored = Files.readString(records.resolve("user-alice/2026-07-26.md"));
        assertThat(stored)
                .contains("- 知识空间：workspace-a")
                .contains("A 空间更新问题")
                .contains("- 知识空间：workspace-b")
                .contains("B 空间问题");
        assertThat(service.detail(user, "workspace-a", "2026-07-26").content())
                .contains("A 空间更新问题")
                .doesNotContain("B 空间问题");
    }

    @Test
    void deletesOnlyTheSelectedWorkspaceLearningEntries() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        Path records = tempDir.resolve("docs/learning-records");
        LearningRecordService service = new LearningRecordService(ingestionService, clock, records);
        AppUser user = new AppUser("alice", "Alice", "hash");

        service.record(user, "workspace-a", "A 空间删除问题", "A 空间删除回答内容。", List.of());
        service.record(user, "workspace-b", "B 空间保留问题", "B 空间保留回答内容。", List.of());

        service.delete(user, "workspace-a", "2026-07-26");

        assertThat(service.list(user, "workspace-a")).isEmpty();
        assertThat(service.detail(user, "workspace-b", "2026-07-26").content())
                .contains("B 空间保留问题")
                .doesNotContain("A 空间删除问题");
        assertThat(Files.readString(records.resolve("user-alice/2026-07-26.md")))
                .contains("B 空间保留问题")
                .doesNotContain("A 空间删除问题");
        verify(ingestionService).deleteIndexedPath(records.resolve("user-alice/2026-07-26.md").toString());
    }

    @Test
    void promotesEachWorkspaceIntoAnIndependentFormalNote() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        Path records = tempDir.resolve("docs/learning-records");
        Path notes = tempDir.resolve("docs/manual-notes");
        LearningRecordService service = new LearningRecordService(ingestionService, clock, records, notes);
        AppUser user = new AppUser("alice", "Alice", "hash");

        service.record(user, "workspace-a", "A 空间正式笔记问题", "A 空间正式笔记回答内容。", List.of());
        service.record(user, "workspace-b", "B 空间正式笔记问题", "B 空间正式笔记回答内容。", List.of());

        FormalNoteResult noteA = service.promote(user, "workspace-a", "2026-07-26", null);
        FormalNoteResult noteB = service.promote(user, "workspace-b", "2026-07-26", null);

        assertThat(noteA.path()).isNotEqualTo(noteB.path());
        assertThat(Files.readString(Path.of(noteA.path())))
                .contains("A 空间正式笔记问题")
                .doesNotContain("B 空间正式笔记问题");
        assertThat(Files.readString(Path.of(noteB.path())))
                .contains("B 空间正式笔记问题")
                .doesNotContain("A 空间正式笔记问题");
        assertThat(service.detail(user, "workspace-a", "2026-07-26").content())
                .contains("A 空间正式笔记问题")
                .doesNotContain("B 空间正式笔记问题");
        assertThat(service.detail(user, "workspace-b", "2026-07-26").content())
                .contains("B 空间正式笔记问题")
                .doesNotContain("A 空间正式笔记问题");
    }

    @Test
    void cannotPromoteAWorkspaceWithoutItsOwnLearningEntry() {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock, tempDir.resolve("docs/learning-records"), tempDir.resolve("docs/manual-notes"));
        AppUser user = new AppUser("alice", "Alice", "hash");
        service.record(user, "workspace-a", "A 空间问题", "A 空间回答内容。", List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.promote(user, "workspace-b", "2026-07-26", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("学习记录不存在");
        assertThat(tempDir.resolve("docs/manual-notes/user-alice")).doesNotExist();
    }

    @Test
    void replacesTheAnswerForARepeatedQuestionWithTheLatestReliableAnswer() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock, tempDir.resolve("docs/learning-records"));
        AppUser user = new AppUser("alice", "Alice", "hash");
        List<RagSource> sources = List.of(new RagSource("rag.md", 0, "", 0.8, "# RAG", "docs/rag.md"));

        service.record(user, "RAG 是什么？", "RAG 先检索再生成。", sources);
        service.record(user, "  RAG 是什么？  ", "RAG 会先从知识库检索相关内容，再组织回答。", sources);

        Path record = tempDir.resolve("docs/learning-records/user-alice/2026-07-26.md");
        assertThat(Files.readString(record))
                .contains("# 2026-07-26 学习记录")
                .contains("RAG 是什么？")
                .doesNotContain("RAG 先检索再生成。")
                .doesNotContain("learning-question:")
                .doesNotContain("回答来源")
                .doesNotContain("docs/rag.md")
                .contains("RAG 会先从知识库检索相关内容，再组织回答。")
                .containsOnlyOnce("## 问题");
        verify(ingestionService, never()).ingestDocument(Mockito.anyString(), eq(true));
    }

    @Test
    void removesReferenceListsFromRecordedAnswers() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock, tempDir.resolve("docs/learning-records"));

        service.record(new AppUser("alice", "Alice", "hash"), "解释向量检索", "向量检索比较语义相似度。\n\n参考来源：\nrag.md / 基本概念", List.of());

        Path record = tempDir.resolve("docs/learning-records/user-alice/2026-07-26.md");
        assertThat(Files.readString(record))
                .contains("向量检索比较语义相似度。")
                .doesNotContain("参考来源")
                .doesNotContain("rag.md")
                .doesNotContain("回答来源");
        verify(ingestionService, never()).ingestDocument(Mockito.anyString(), eq(true));
    }

    @Test
    void removesModelKnowledgeDisclaimerFromRecordedAnswers() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock, tempDir.resolve("docs/learning-records"));

        service.record(new AppUser("alice", "Alice", "hash"), "解释 RAG",
                "RAG 会先检索资料再生成回答。\n\n以上回答基于通用大模型知识，不是当前知识库内容。", List.of());

        String content = Files.readString(tempDir.resolve("docs/learning-records/user-alice/2026-07-26.md"));
        assertThat(content)
                .contains("RAG 会先检索资料再生成回答。")
                .doesNotContain("以上回答基于通用大模型知识")
                .doesNotContain("不是当前知识库内容");
    }

    @Test
    void doesNotRecordKnownNoAnswerResponses() {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock, tempDir.resolve("docs/learning-records"));

        service.record(
                new AppUser("alice", "Alice", "hash"),
                "一个知识库里没有答案的问题",
                "我在当前知识库中没有找到足够信息和依据来回答这个问题。你可以导入相关文档后再问。",
                List.of()
        );

        assertThat(tempDir.resolve("docs/learning-records/user-alice/2026-07-26.md")).doesNotExist();
        verify(ingestionService, never()).ingestDocument(Mockito.anyString(), eq(true));
    }

    @Test
    void doesNotRecordEmptyKnowledgeBaseResponses() {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock, tempDir.resolve("docs/learning-records"));

        service.record(
                new AppUser("alice", "Alice", "hash"),
                "总结当前知识库的主要内容",
                "当前知识库没有包含任何信息。",
                List.of()
        );

        assertThat(tempDir.resolve("docs/learning-records/user-alice/2026-07-26.md")).doesNotExist();
        verify(ingestionService, never()).ingestDocument(Mockito.anyString(), eq(true));
    }

    @Test
    void doesNotRecordHeadingOnlyFallbacks() {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        LearningRecordService service = new LearningRecordService(ingestionService, clock, tempDir.resolve("docs/learning-records"));

        service.record(new AppUser("alice", "Alice", "hash"), "RAG 是什么？", "# RAG\n\n参考来源：\nrag.md / RAG", List.of());

        assertThat(tempDir.resolve("docs/learning-records/user-alice/2026-07-26.md")).doesNotExist();
        verify(ingestionService, never()).ingestDocument(Mockito.anyString(), eq(true));
    }

    @Test
    void promotesEditedContentAndKeepsTheLearningRecordInSync() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        Path records = tempDir.resolve("docs/learning-records");
        Path notes = tempDir.resolve("docs/manual-notes");
        LearningRecordService service = new LearningRecordService(ingestionService, clock, records, notes);
        AppUser user = new AppUser("alice", "Alice", "hash");
        service.record(user, "RAG 是什么？", "RAG 会先检索资料再生成回答。", List.of());

        LearningRecordDetail updated = service.update(user, "2026-07-26", "# 整理后的记录\n\n这是编辑后的学习内容。");
        FormalNoteResult note = service.promote(user, "2026-07-26", "# 最终整理内容\n\n这是提升时尚未单独保存的编辑内容。");

        assertThat(updated.content()).contains("编辑后的学习内容");
        assertThat(note.fileName()).isEqualTo("2026-07-26-learning-note.md");
        assertThat(Files.readString(notes.resolve("user-alice/2026-07-26-learning-note.md")))
                .contains("# 2026-07-26 正式笔记")
                .contains("提升时尚未单独保存的编辑内容");
        assertThat(Files.readString(records.resolve("user-alice/2026-07-26.md")))
                .contains("# 2026-07-26 学习记录")
                .contains("提升时尚未单独保存的编辑内容")
                .doesNotContain("# 2026-07-26 正式笔记");
        verify(ingestionService).deleteIndexedPath(notes.resolve("user-alice/2026-07-26-learning-note.md").toString());
        verify(ingestionService).deleteIndexedPath(records.resolve("user-alice/2026-07-26.md").toString());
        verify(ingestionService, times(1)).ingestDocument(eq(notes.resolve("user-alice/2026-07-26-learning-note.md").toString()), eq(true));
    }

    @Test
    void promotesTheSameRecordWithoutNestingFormalNoteHeaders() throws Exception {
        DocumentIngestionService ingestionService = Mockito.mock(DocumentIngestionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-26T09:00:00Z"), ZoneId.of("UTC"));
        Path records = tempDir.resolve("docs/learning-records");
        Path notes = tempDir.resolve("docs/manual-notes");
        LearningRecordService service = new LearningRecordService(ingestionService, clock, records, notes);
        AppUser user = new AppUser("alice", "Alice", "hash");
        service.record(user, "RAG 是什么？", "RAG 会先检索资料再生成回答。", List.of());

        service.promote(user, "2026-07-26");
        service.promote(user, "2026-07-26");

        String content = Files.readString(notes.resolve("user-alice/2026-07-26-learning-note.md"));
        assertThat(content).containsOnlyOnce("# 2026-07-26 正式笔记");
        assertThat(Files.readString(records.resolve("user-alice/2026-07-26.md")))
                .containsOnlyOnce("# 2026-07-26 学习记录")
                .doesNotContain("# 2026-07-26 正式笔记");
    }
}
