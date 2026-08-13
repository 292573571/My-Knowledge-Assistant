package com.example.workbench.learning;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning-records")
public class LearningRecordController {

    private final LearningRecordService learningRecordService;
    private final WorkspaceService workspaceService;

    public LearningRecordController(LearningRecordService learningRecordService, WorkspaceService workspaceService) {
        this.learningRecordService = learningRecordService;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public List<LearningRecordSummary> list(@RequestParam String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return learningRecordService.list(user, workspaceId);
    }

    @GetMapping("/teaching-progress")
    public List<TeachingTopicProgress> teachingProgress(@RequestParam String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return learningRecordService.teachingProgress(user, workspaceId);
    }

    @GetMapping("/{date}")
    public LearningRecordDetail detail(@PathVariable String date, @RequestParam String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return learningRecordService.detail(user, workspaceId, date);
    }

    @PutMapping("/{date}")
    public LearningRecordDetail update(@PathVariable String date, @RequestParam String workspaceId, @RequestBody UpdateLearningRecordRequest body, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return learningRecordService.update(user, workspaceId, date, body.content());
    }

    @DeleteMapping("/{date}")
    public void delete(@PathVariable String date, @RequestParam String workspaceId, HttpServletRequest request) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        learningRecordService.delete(user, workspaceId, date);
    }

    @PostMapping("/{date}/promote")
    public FormalNoteResult promote(
            @PathVariable String date,
            @RequestParam String workspaceId,
            @RequestBody(required = false) PromoteLearningRecordRequest body,
            HttpServletRequest request
    ) {
        AppUser user = user(request);
        workspaceService.access(user, workspaceId);
        return learningRecordService.promote(user, workspaceId, date, body == null ? null : body.content());
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
