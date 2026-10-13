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
import org.springframework.web.bind.annotation.RestController;

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

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
