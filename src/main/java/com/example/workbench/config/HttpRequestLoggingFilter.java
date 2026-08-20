package com.example.workbench.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import io.opentelemetry.api.trace.Span;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = HttpRequestLoggingFilter.class.getName() + ".requestId";
    private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        String requestId = requestId();
        String traceId = traceId(request);
        String spanId = spanId();
        String path = request.getRequestURI();

        ThreadContext.clearAll();
        LoggingContext.putDeploymentContext();
        ThreadContext.put("requestId", requestId);
        ThreadContext.put("traceId", traceId);
        LoggingContext.put(LoggingContext.SPAN_ID, spanId);
        ThreadContext.put("apiLogFile", LocalDate.now() + "/" + apiLogFileName(request));
        ThreadContext.put("apiLogArchiveFile", LocalDate.now() + "/archive/" + apiArchiveFileName(request));
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Trace-Id", traceId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);

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
        return UUID.randomUUID().toString();
    }

    private String traceId(HttpServletRequest request) {
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            return span.getSpanContext().getTraceId();
        }
        String value = request.getHeader("X-Trace-Id");
        if (value != null && value.strip().matches("[0-9a-fA-F]{32}")) {
            return value.strip().toLowerCase();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String spanId() {
        Span span = Span.current();
        return span.getSpanContext().isValid() ? span.getSpanContext().getSpanId() : "none";
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
