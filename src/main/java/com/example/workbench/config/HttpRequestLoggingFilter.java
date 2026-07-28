package com.example.workbench.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    private static final int MAX_BODY_LOG_LENGTH = 4_000;
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|authorization|token|password|secret)(\\s*[=:]\\s*|\"\\s*:\\s*\")[^,}\\s\"]+"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        String requestId = requestId();
        String queryString = request.getQueryString();
        String path = queryString == null ? request.getRequestURI() : request.getRequestURI() + "?" + queryString;
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = shouldWrapResponse(request)
                ? new ContentCachingResponseWrapper(response)
                : null;

        ThreadContext.put("requestId", requestId);
        ThreadContext.put("apiLogFile", LocalDate.now() + "/" + apiLogFileName(request));
        ThreadContext.put("apiLogArchiveFile", LocalDate.now() + "/archive/" + apiArchiveFileName(request));
        response.setHeader("X-Request-Id", requestId);

        log.info(
                "HTTP request started requestId={} method={} path={} remote={} query={}",
                requestId,
                request.getMethod(),
                path,
                request.getRemoteAddr(),
                sanitize(queryString == null ? "" : queryString)
        );

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse == null ? response : wrappedResponse);
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            int status = wrappedResponse == null ? response.getStatus() : wrappedResponse.getStatus();
            String requestBody = requestBody(wrappedRequest);
            String responseBody = wrappedResponse == null ? "<streaming response not captured>" : responseBody(wrappedResponse);

            if (status >= 500) {
                log.error(
                        "HTTP request failed requestId={} method={} path={} status={} durationMs={} query={} requestBody={} responseBody={}",
                        requestId,
                        request.getMethod(),
                        path,
                        status,
                        durationMs,
                        sanitize(queryString == null ? "" : queryString),
                        requestBody,
                        responseBody
                );
            } else if (status >= 400) {
                log.warn(
                        "HTTP request completed with client error requestId={} method={} path={} status={} durationMs={} query={} requestBody={} responseBody={}",
                        requestId,
                        request.getMethod(),
                        path,
                        status,
                        durationMs,
                        sanitize(queryString == null ? "" : queryString),
                        requestBody,
                        responseBody
                );
            } else {
                log.info(
                        "HTTP request completed requestId={} method={} path={} status={} durationMs={} query={} requestBody={} responseBody={}",
                        requestId,
                        request.getMethod(),
                        path,
                        status,
                        durationMs,
                        sanitize(queryString == null ? "" : queryString),
                        requestBody,
                        responseBody
                );
            }

            if (wrappedResponse != null) {
                wrappedResponse.copyBodyToResponse();
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

    private boolean shouldWrapResponse(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return !request.getRequestURI().endsWith("/stream")
                && (accept == null || !accept.contains("text/event-stream"));
    }

    private String requestBody(ContentCachingRequestWrapper request) {
        byte[] content = request.getContentAsByteArray();
        if (content.length == 0) {
            return "";
        }

        Charset charset = charsetFrom(request.getCharacterEncoding(), request.getContentType());
        return truncate(sanitize(new String(content, charset)));
    }

    private String responseBody(ContentCachingResponseWrapper response) {
        byte[] content = response.getContentAsByteArray();
        if (content.length == 0) {
            return "";
        }

        Charset charset = charsetFrom(response.getCharacterEncoding(), response.getContentType());
        return truncate(sanitize(new String(content, charset)));
    }

    private Charset charsetFrom(String characterEncoding, String contentType) {
        Charset contentTypeCharset = charsetFromContentType(contentType);
        if (contentTypeCharset != null) {
            return contentTypeCharset;
        }

        if (characterEncoding == null || characterEncoding.isBlank() || "ISO-8859-1".equalsIgnoreCase(characterEncoding)) {
            return StandardCharsets.UTF_8;
        }

        try {
            return Charset.forName(characterEncoding);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException exception) {
            return StandardCharsets.UTF_8;
        }
    }

    private Charset charsetFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }

        for (String part : contentType.split(";")) {
            String value = part.trim();
            if (!value.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
                continue;
            }

            try {
                return Charset.forName(value.substring("charset=".length()).trim());
            } catch (IllegalCharsetNameException | UnsupportedCharsetException exception) {
                return StandardCharsets.UTF_8;
            }
        }

        return null;
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return SECRET_PATTERN.matcher(value).replaceAll("$1$2***");
    }

    private String truncate(String value) {
        if (value.length() <= MAX_BODY_LOG_LENGTH) {
            return value;
        }

        return value.substring(0, MAX_BODY_LOG_LENGTH) + "...<truncated>";
    }
}
