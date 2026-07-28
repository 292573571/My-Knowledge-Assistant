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

class LearningRecordServiceTest {

    @TempDir
    Path tempDir;

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
                .isEqualTo(Files.readString(notes.resolve("user-alice/2026-07-26-learning-note.md")));
        verify(ingestionService).deleteIndexedPath(Mockito.anyString());
        verify(ingestionService, times(1)).ingestDocument(eq(notes.resolve("user-alice/2026-07-26-learning-note.md").toString()), eq(true));
    }
}
