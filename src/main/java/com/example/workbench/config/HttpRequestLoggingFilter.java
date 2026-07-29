package com.example.workbench.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        String requestId = requestId();
        String path = request.getRequestURI();

        ThreadContext.put("requestId", requestId);
        ThreadContext.put("apiLogFile", LocalDate.now() + "/" + apiLogFileName(request));
        ThreadContext.put("apiLogArchiveFile", LocalDate.now() + "/archive/" + apiArchiveFileName(request));
        response.setHeader("X-Request-Id", requestId);

        log.info(
                "HTTP request started requestId={} method={} path={}",
                requestId,
                request.getMethod(),
                path
        );

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            int status = response.getStatus();

            if (status >= 500) {
                log.error(
                        "HTTP request failed requestId={} method={} path={} status={} durationMs={} requestBytes={}",
                        requestId,
                        request.getMethod(),
                        path,
                        status,
                        durationMs,
                        Math.max(0, request.getContentLengthLong())
                );
            } else if (status >= 400) {
                log.warn(
                        "HTTP request completed with client error requestId={} method={} path={} status={} durationMs={} requestBytes={}",
                        requestId,
                        request.getMethod(),
                        path,
                        status,
                        durationMs,
                        Math.max(0, request.getContentLengthLong())
                );
            } else {
                log.info(
                        "HTTP request completed requestId={} method={} path={} status={} durationMs={} requestBytes={}",
                        requestId,
                        request.getMethod(),
                        path,
                        status,
                        durationMs,
                        Math.max(0, request.getContentLengthLong())
                );
            }

            ThreadContext.clearAll();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api");
    }

    private String requestId() {
        return Long.toUnsignedString(System.nanoTime(), 36);
    }

    private String apiLogFileName(HttpServletRequest request) {
        return apiName(request) + ".log";
    }

    private String apiArchiveFileName(HttpServletRequest request) {
        return apiName(request) + "-%i.log.gz";
    }

    private String apiName(HttpServletRequest request) {
        String value = request.getRequestURI();
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

}
