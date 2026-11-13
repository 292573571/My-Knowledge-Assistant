package com.example.workbench.conversation;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import com.example.workbench.auth.AdminAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatFeedbackController {

    private final ChatFeedbackService feedbackService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ChatFeedbackController(ChatFeedbackService feedbackService, AdminAuthorizationService adminAuthorizationService) {
        this.feedbackService = feedbackService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    public record FeedbackSubmitRequest(
            @NotNull @Min(-1) @Max(1) Integer rating,
            @Size(max = 500) String reason) { }

    @PostMapping("/chat-messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatFeedbackService.FeedbackResponse submit(@PathVariable Long messageId,
                                                       @RequestParam String workspaceId,
                                                       @RequestParam String conversationId,
                                                       @Valid @RequestBody FeedbackSubmitRequest request,
                                                       HttpServletRequest httpRequest) {
        AppUser user = authenticatedUser(httpRequest);
        return feedbackService.submit(user, workspaceId, conversationId, messageId,
                request.rating(), request.reason());
    }

    /** 管理员反馈池：rating=-1 只看差评，rating=1 只看好评，缺省全量。 */
    @GetMapping("/feedback")
    public List<ChatFeedbackService.AdminFeedbackItem> list(@RequestParam String workspaceId,
                                                            @RequestParam(required = false) Integer rating,
                                                            HttpServletRequest httpRequest) {
        adminAuthorizationService.requireAdmin(authenticatedUser(httpRequest));
        return feedbackService.listForAdmin(workspaceId, rating);
    }

    private AppUser authenticatedUser(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
