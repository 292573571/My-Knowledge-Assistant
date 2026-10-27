package com.example.workbench.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class WikiImportTaskServiceTest {

    private final WikiPageService wikiPageService = mock(WikiPageService.class);

    private WikiImportTaskService service() {
        // 同步执行器：submit 后任务立即跑完，便于断言终态
        return new WikiImportTaskService(wikiPageService, Runnable::run);
    }

    private AppUser user(long id) {
        AppUser user = new AppUser("user-" + id, "用户" + id, "hash");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private MockMultipartFile upload() {
        return new MockMultipartFile("file", "notes.md", "text/markdown", "# 标题\n正文".getBytes());
    }

    @Test
    void submitRunsTaskAndPollReturnsDraft() throws Exception {
        when(wikiPageService.importFromFile(eq(null), eq("ws-1"), any()))
                .thenReturn(new WikiImportContent("notes.md", "标题", "# 标题\n正文"));

        WikiImportTaskService taskService = service();
        String taskId = taskService.submit(user(1L), "ws-1", upload());

        WikiImportTaskResponse response = taskService.poll(user(1L), taskId);
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.title()).isEqualTo("标题");
        assertThat(response.contentMd()).contains("正文");
        // 任务受理时的临时文件应在任务结束后清理
        assertThat(countStagedFiles()).isZero();
    }

    @Test
    void pollReturnsFailedWhenParsingThrows() throws Exception {
        when(wikiPageService.importFromFile(eq(null), eq("ws-1"), any()))
                .thenThrow(new IllegalArgumentException("不支持的文件格式"));

        WikiImportTaskService taskService = service();
        String taskId = taskService.submit(user(1L), "ws-1", upload());

        WikiImportTaskResponse response = taskService.poll(user(1L), taskId);
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.error()).contains("不支持的文件格式");
        assertThat(countStagedFiles()).isZero();
    }

    @Test
    void pollOfOtherUserOrMissingTaskReturnsNull() throws Exception {
        when(wikiPageService.importFromFile(eq(null), eq("ws-1"), any()))
                .thenReturn(new WikiImportContent("notes.md", "标题", "内容"));

        WikiImportTaskService taskService = service();
        String taskId = taskService.submit(user(1L), "ws-1", upload());

        assertThat(taskService.poll(user(2L), taskId)).isNull();
        assertThat(taskService.poll(user(1L), "no-such-task")).isNull();
    }

    /** 统计系统临时目录里的 wiki-import 暂存文件，用于验证清理。 */
    private int countStagedFiles() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> found;
        try (var stream = Files.list(tmp)) {
            found = stream.filter(path -> path.getFileName().toString().startsWith("wiki-import-")).toList();
        }
        return found.size();
    }
}
