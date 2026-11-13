package com.example.workbench.search;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public List<SearchService.SearchHit> search(@RequestParam String q,
                                                @RequestParam String workspaceId,
                                                @RequestParam(required = false) Integer limit,
                                                HttpServletRequest request) {
        return searchService.search(authenticatedUser(request), q, workspaceId, limit);
    }

    private AppUser authenticatedUser(HttpServletRequest httpRequest) {
        return (AppUser) httpRequest.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }
}
