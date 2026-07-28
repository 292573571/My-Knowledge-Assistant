package com.example.workbench.learning;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

    public LearningRecordController(LearningRecordService learningRecordService) {
        this.learningRecordService = learningRecordService;
    }

    @GetMapping
    public List<LearningRecordSummary> list(HttpServletRequest request) {
        return learningRecordService.list(user(request));
    }

    @GetMapping("/{date}")
    public LearningRecordDetail detail(@PathVariable String date, HttpServletRequest request) {
        return learningRecordService.detail(user(request), date);
    }

    @PutMapping("/{date}")
    public LearningRecordDetail update(@PathVariable String date, @RequestBody UpdateLearningRecordRequest body, HttpServletRequest request) {
        return learningRecordService.update(user(request), date, body.content());
    }

    @DeleteMapping("/{date}")
    public void delete(@PathVariable String date, HttpServletRequest request) {
        learningRecordService.delete(user(request), date);
    }

    @PostMapping("/{date}/promote")
    public FormalNoteResult promote(
            @PathVariable String date,
            @RequestBody(required = false) PromoteLearningRecordRequest body,
            HttpServletRequest request
    ) {
        return learningRecordService.promote(user(request), date, body == null ? null : body.content());
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
