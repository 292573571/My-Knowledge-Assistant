package com.example.workbench.wiki;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/wiki-pages")
public class WikiPageController {

    private final WikiPageService wikiPageService;
    private final WorkspaceService workspaceService;
    private final WikiImportTaskService importTaskService;

    public WikiPageController(WikiPageService wikiPageService, WorkspaceService workspaceService,
                              WikiImportTaskService importTaskService) {
        this.wikiPageService = wikiPageService;
        this.workspaceService = workspaceService;
        this.importTaskService = importTaskService;
    }

    @PostMapping
    public WikiPageDto create(@RequestParam String workspaceId, @RequestBody WikiPageRequest body, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return wikiPageService.create(user, workspaceId, body);
    }

    @GetMapping
    public List<WikiPageDto> list(@RequestParam String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return wikiPageService.list(workspaceId);
    }

    @GetMapping("/{id}")
    public WikiPageDto get(@PathVariable String id, @RequestParam String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return wikiPageService.get(workspaceId, id);
    }

    @PutMapping("/{id}")
    public WikiPageDto update(@PathVariable String id, @RequestParam String workspaceId,
                              @RequestBody WikiPageRequest body, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return wikiPageService.update(user, workspaceId, id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        wikiPageService.delete(user, workspaceId, id);
    }

    /**
     * 受理 wiki 导入任务：立即返回 taskId，后台解析（大文件 OCR 可能耗时数分钟）。
     * 前端用 {@code GET /import/{taskId}} 轮询，SUCCEEDED 后拿草稿填入编辑器。
     */
    @PostMapping("/import")
    public WikiImportTaskResponse importFromFile(@RequestParam String workspaceId,
                                                 @RequestPart("file") MultipartFile file,
                                                 HttpServletRequest request) throws IOException {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        String taskId = importTaskService.submit(user, workspaceId, file);
        return WikiImportTaskResponse.running(taskId);
    }

    /** 轮询导入任务：任务不存在（重启丢失 / 过期清理 / 非本人）返回 404。 */
    @GetMapping("/import/{taskId}")
    public WikiImportTaskResponse importTask(@PathVariable String taskId,
                                             @RequestParam String workspaceId,
                                             HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        WikiImportTaskResponse response = importTaskService.poll(user, taskId);
        if (response == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "导入任务不存在或已过期，请重新导入。");
        }
        return response;
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
