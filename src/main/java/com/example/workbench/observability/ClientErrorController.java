package com.example.workbench.observability;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端运行时错误上报入口。
 *
 * <p>前端白屏、组件渲染异常过去只能靠用户截图反馈，服务端完全无感知。这里接收
 * 已认证用户上报的异常，用 ERROR 级别写入日志——落库与 error 文件由既有的
 * log4j2 Jpa/ErrorFile appender 完成，不新增表、不新增写路径。
 *
 * <p>安全约束：只接受已登录用户；按用户每分钟限流；所有前端可控字段都做换行清洗
 * 与长度截断，避免日志注入与单条日志膨胀。
 */
@RestController
@RequestMapping("/api/client-errors")
public class ClientErrorController {

    private static final Logger DEFAULT_LOG = LoggerFactory.getLogger("com.example.workbench.client");

    private static final int MAX_PER_MINUTE = 20;
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_STACK_LENGTH = 2000;
    private static final int MAX_FIELD_LENGTH = 200;

    private Logger log = DEFAULT_LOG;

    /** 仅测试使用：替换 logger 以便断言实际写出的内容与条数。 */
    void setLogger(Logger logger) {
        this.log = logger;
    }

    /** userId -> [当前分钟, 本分钟已上报条数]。只用于限流，不做持久化。 */
    private final Map<Long, long[]> rateWindow = new ConcurrentHashMap<>();

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@RequestBody(required = false) ClientErrorPayload payload, HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (payload == null || user == null || user.getId() == null) {
            return;
        }
        if (!allow(user.getId())) {
            return;
        }
        String message = clean(payload.message(), MAX_MESSAGE_LENGTH);
        if (message.isBlank()) {
            return;
        }
        log.error("Client error userId={} kind={} page={} message={} stack={} userAgent={}",
                user.getId(),
                clean(payload.kind(), 32),
                clean(payload.page(), MAX_FIELD_LENGTH),
                message,
                clean(payload.stack(), MAX_STACK_LENGTH),
                clean(payload.userAgent(), MAX_FIELD_LENGTH));
    }

    private boolean allow(Long userId) {
        long minute = System.currentTimeMillis() / 60_000L;
        if (rateWindow.size() > 10_000) {
            rateWindow.clear();
        }
        long[] slot = rateWindow.compute(userId, (key, previous) ->
                previous == null || previous[0] != minute
                        ? new long[]{minute, 1}
                        : new long[]{minute, previous[1] + 1});
        return slot[1] <= MAX_PER_MINUTE;
    }

    /**
     * 换行统一折叠成 " | "：前端可控内容不能伪造出新的日志行，也不能撑爆单行日志。
     */
    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", " | ")
                .replace("\r", " ")
                .replace("\n", " | ")
                .replace("\t", " ")
                .strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    public record ClientErrorPayload(String kind, String message, String stack, String page, String userAgent) {
    }
}
