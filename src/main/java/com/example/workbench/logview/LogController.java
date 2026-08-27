package com.example.workbench.logview;

import com.example.workbench.auth.AdminAuthorizationService;
import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);
    private static final ZoneId ZONE_CN = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<String, List<String>> LEVEL_ABOVE = Map.of(
            "ERROR", List.of("ERROR"),
            "WARN", List.of("ERROR", "WARN"),
            "INFO", List.of("ERROR", "WARN", "INFO"),
            "DEBUG", List.of("ERROR", "WARN", "INFO", "DEBUG")
    );

    private final AdminAuthorizationService adminAuthorizationService;
    private final SystemLogRepository systemLogRepository;

    public LogController(AdminAuthorizationService adminAuthorizationService,
                         SystemLogRepository systemLogRepository) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.systemLogRepository = systemLogRepository;
    }

    @GetMapping
    public LogResponse query(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int hours,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String exceptionType,
            HttpServletRequest request
    ) {
        AppUser user = user(request);
        adminAuthorizationService.requireAdmin(user);

        if (page < 0) page = 0;
        if (size < 1 || size > 500) size = 100;
        if (hours < 0) hours = 0;

        Instant since = hours == 0 ? null : Instant.now().minus(hours, ChronoUnit.HOURS);
        List<String> levels = level != null && !level.isBlank() ? LEVEL_ABOVE.getOrDefault(level.strip().toUpperCase(), null) : null;
        String kw = keyword != null && !keyword.isBlank() ? keyword.strip() : null;

        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "timestamp").and(Sort.by(Sort.Direction.DESC, "id")));
        Specification<SystemLog> specification = Specification.where(null);
        if (levels != null) specification = specification.and((root, query, builder) -> root.get("level").in(levels));
        if (kw != null) specification = specification.and((root, query, builder) ->
                builder.like(builder.lower(root.get("message")), "%" + kw.toLowerCase() + "%"));
        if (since != null) specification = specification.and((root, query, builder) ->
                builder.greaterThan(root.get("timestamp"), since));
        specification = exact(specification, "requestId", requestId);
        specification = exact(specification, "traceId", traceId);
        specification = exact(specification, "userId", userId);
        specification = exact(specification, "workspaceId", workspaceId);
        specification = exact(specification, "instanceId", instanceId);
        specification = exact(specification, "environment", environment);
        specification = exact(specification, "exceptionType", exceptionType);
        Page<SystemLog> result = systemLogRepository.findAll(specification, pageable);

        List<LogEntry> entries = result.getContent().stream()
                .map(l -> new LogEntry(l.getId(), FMT.format(l.getTimestamp().atZone(ZONE_CN)), l.getLevel(), l.getLogger(), l.getThread(),
                        l.getMessage(), l.getRequestId(), l.getTraceId(), l.getUserId(), l.getWorkspaceId(), l.getInstanceId(),
                        l.getEnvironment(), l.getExceptionType(), l.getStackTrace()))
                .toList();

        return new LogResponse(result.getTotalElements(), page, result.getTotalPages(), entries);
    }

    private Specification<SystemLog> exact(Specification<SystemLog> specification, String field, String value) {
        if (value == null || value.isBlank()) return specification;
        String normalized = value.strip();
        return specification.and((root, query, builder) -> builder.equal(root.get(field), normalized));
    }

    @Scheduled(fixedRateString = "${workbench.log.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupOldLogs() {
        try {
            Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
            int deleted = systemLogRepository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Cleaned up old system logs olderThan={} deleted={}", cutoff, deleted);
            }
        } catch (Exception e) {
            log.warn("Log cleanup failed", e);
        }
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest request) {
        AppUser user = user(request);
        adminAuthorizationService.requireAdmin(user);
        long count = systemLogRepository.count();
        systemLogRepository.deleteAll();
        log.info("All system logs cleared count={}", count);
        return Map.of("deleted", count);
    }

    public record LogEntry(long id, String timestamp, String level, String logger, String thread, String message,
                           String requestId, String traceId, String userId, String workspaceId, String instanceId,
                           String environment, String exceptionType, String stackTrace) {}
    public record LogResponse(long total, int page, int totalPages, List<LogEntry> entries) {}
}
