package com.example.workbench.observability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.workbench.auth.AppUser;
import com.example.workbench.auth.AuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientErrorControllerTest {

    private static final String TEMPLATE =
            "Client error userId={} kind={} page={} message={} stack={} userAgent={}";

    private final ClientErrorController controller = new ClientErrorController();
    private final Logger logger = mock(Logger.class);

    @BeforeEach
    void setUp() {
        controller.setLogger(logger);
    }

    @Test
    @DisplayName("上报内容折叠换行后落 ERROR 日志")
    void reportsErrorWithSanitizedText() {
        controller.report(new ClientErrorController.ClientErrorPayload(
                "vue:render", "Cannot read properties of null\n    at App.vue:12", "stack\nline2",
                "/workspace/1", "Mozilla/5.0"), authenticated(7L));

        verify(logger).error(eq(TEMPLATE),
                eq(7L),
                eq("vue:render"),
                eq("/workspace/1"),
                eq("Cannot read properties of null |     at App.vue:12"),
                contains("stack | line2"),
                eq("Mozilla/5.0"));
    }

    @Test
    @DisplayName("未认证或空载荷静默丢弃")
    void ignoresAnonymous() {
        controller.report(new ClientErrorController.ClientErrorPayload(
                "vue:render", "boom", null, "/", null), new MockHttpServletRequest());
        controller.report(null, authenticated(7L));

        verify(logger, never()).error(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("同一用户每分钟最多 20 条")
    void rateLimitsPerUser() {
        MockHttpServletRequest request = authenticated(9L);

        for (int index = 1; index <= 25; index++) {
            controller.report(new ClientErrorController.ClientErrorPayload(
                    "window:error", "burst-" + index, null, "/", null), request);
        }

        verify(logger, times(20)).error(eq(TEMPLATE),
                eq(9L), eq("window:error"), eq("/"),
                anyString(), anyString(), anyString());
    }

    private MockHttpServletRequest authenticated(Long userId) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(userId);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user);
        return request;
    }
}
