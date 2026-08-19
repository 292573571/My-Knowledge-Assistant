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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
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
            @RequestParam(defaultValue = "2") int hours,
            HttpServletRequest request
    ) {
        AppUser user = user(request);
        adminAuthorizationService.requireAdmin(user);

        if (page < 0) page = 0;
        if (size < 1 || size > 500) size = 100;
        if (hours < 1) hours = 2;

        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<String> levels = level != null && !level.isBlank() ? LEVEL_ABOVE.getOrDefault(level.strip().toUpperCase(), null) : null;
        String kw = keyword != null && !keyword.isBlank() ? keyword.strip() : null;

        Page<SystemLog> result;
        PageRequest pageable = PageRequest.of(page, size);

        if (levels != null && kw != null) {
            result = systemLogRepository.findByLevelInAndMessageContainingIgnoreCaseAndTimestampAfterOrderByTimestampDesc(levels, kw, since, pageable);
        } else if (levels != null) {
            result = systemLogRepository.findByLevelInAndTimestampAfterOrderByTimestampDesc(levels, since, pageable);
        } else if (kw != null) {
            result = systemLogRepository.findByMessageContainingIgnoreCaseAndTimestampAfterOrderByTimestampDesc(kw, since, pageable);
        } else {
            result = systemLogRepository.findByTimestampAfterOrderByTimestampDesc(since, pageable);
        }

        List<LogEntry> entries = result.getContent().stream()
                .map(l -> new LogEntry(l.getId(), FMT.format(l.getTimestamp().atZone(ZONE_CN)), l.getLevel(), l.getLogger(), l.getThread(), l.getMessage()))
                .toList();

        return new LogResponse(result.getTotalElements(), page, result.getTotalPages(), entries);
    }

    @Scheduled(fixedRateString = "${workbench.log.cleanup-interval-ms:3600000}")
    public void cleanupOldLogs() {
        try {
            Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
            int deleted = systemLogRepository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Cleaned up old system logs olderThan={} deleted={}", cutoff, deleted);
            }
        } catch (Exception e) {
            log.warn("Log cleanup failed: {}", e.getMessage());
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

    public record LogEntry(long id, String timestamp, String level, String logger, String thread, String message) {}
    public record LogResponse(long total, int page, int totalPages, List<LogEntry> entries) {}
}
