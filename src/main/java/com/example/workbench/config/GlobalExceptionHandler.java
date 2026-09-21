package com.example.workbench.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.workbench.auth.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationException(MethodArgumentNotValidException exception,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) throws IOException {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数不正确");
        return respond(request, response, HttpStatus.BAD_REQUEST, message, "validation_error", false);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgumentException(IllegalArgumentException exception,
                                                             HttpServletRequest request,
                                                             HttpServletResponse response) throws IOException {
        return respond(request, response, HttpStatus.BAD_REQUEST,
                exception.getMessage(), "illegal_argument", false);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<?> handleInvalidCredentialsException(InvalidCredentialsException exception,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) throws IOException {
        return respond(request, response, HttpStatus.UNAUTHORIZED,
                exception.getMessage(), "invalid_credentials", false);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleResponseStatusException(ResponseStatusException exception,
                                                            HttpServletRequest request,
                                                            HttpServletResponse response) throws IOException {
        String message = exception.getReason() == null ? "请求无法处理" : exception.getReason();
        int status = exception.getStatusCode().value();
        return respond(request, response, HttpStatus.valueOf(status), message,
                "response_status", status == 408 || status == 429 || status >= 500);
    }

    @ExceptionHandler(ModelProviderException.class)
    public ResponseEntity<?> handleModelProviderException(ModelProviderException exception,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) throws IOException {
        log.warn("模型服务调用异常 errorCode={} traceId={} message={}",
                exception.getErrorCode(), exception.getTraceId(), exception.getUserMessage());
        return respond(request, response, HttpStatus.valueOf(exception.getHttpStatus()),
                exception.getUserMessage(), exception.getErrorCode(), exception.isRetryable());
    }

    /**
     * 静态资源/未知路径 404:浏览器探测(favicon.ico、robots.txt)与爬虫扫描会高频触发,
     * 这属于预期内的客户端行为,不再按 ERROR 记录,避免日志与日志中心被噪音刷屏。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResourceFoundException(NoResourceFoundException exception,
                                                             HttpServletRequest request,
                                                             HttpServletResponse response) throws IOException {
        log.debug("静态资源不存在 method={} uri={} resource={}",
                request.getMethod(), request.getRequestURI(), exception.getResourcePath());
        return respond(request, response, HttpStatus.NOT_FOUND, "资源不存在", "resource_not_found", false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpectedException(Exception exception,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) throws IOException {
        log.error("未处理的请求异常 errorType={} message={}", exception.getClass().getSimpleName(), exception.getMessage(), exception);
        return respond(request, response, HttpStatus.INTERNAL_SERVER_ERROR,
                "服务暂时不可用，请稍后重试", "unexpected", true);
    }

    /**
     * 统一出口:对 SSE 请求直接写 {@code event: error} 片段,对普通请求才返回 JSON。
     *
     * <p>SSE 端点声明了 {@code produces = text/event-stream},客户端 Accept 只接受该类型。
     * 若这里仍返回 JSON,内容协商会抛 HttpMediaTypeNotAcceptableException,异常处理器自身失败,
     * 前端拿到的只是兜底文案而看不到真实原因。因此 SSE 场景必须自己写流。</p>
     */
    private ResponseEntity<?> respond(HttpServletRequest request, HttpServletResponse response,
                                      HttpStatus status, String message, String errorType,
                                      boolean retryable) throws IOException {
        if (prefersEventStream(request)) {
            writeEventStreamError(response, status, message, errorType, retryable);
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(status).body(new ApiErrorResponse(message));
    }

    private boolean prefersEventStream(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private void writeEventStreamError(HttpServletResponse response, HttpStatus status, String message,
                                       String errorType, boolean retryable) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message == null ? "请求失败" : message);
        payload.put("errorType", errorType);
        payload.put("status", status.value());
        payload.put("retryable", retryable);
        response.setStatus(status.value());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter writer = response.getWriter();
        writer.write("event: error\n");
        writer.write("data: ");
        writer.write(OBJECT_MAPPER.writeValueAsString(payload));
        writer.write("\n\n");
        writer.flush();
    }
}
