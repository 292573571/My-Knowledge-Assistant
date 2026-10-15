package com.example.workbench.wiki;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
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

    public WikiPageController(WikiPageService wikiPageService, WorkspaceService workspaceService) {
        this.wikiPageService = wikiPageService;
        this.workspaceService = workspaceService;
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
     * 将上传文件解析为 wiki 页面草稿（PDF / Word / 文本等），不落库。
     * 前端把返回内容填入编辑器，用户确认保存时再走创建链路投影进 RAG 检索。
     */
    @PostMapping("/import")
    public WikiImportContent importFromFile(@RequestParam String workspaceId,
                                           @RequestPart("file") MultipartFile file,
                                           HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return wikiPageService.importFromFile(user, workspaceId, file);
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
