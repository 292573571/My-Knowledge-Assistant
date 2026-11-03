package com.example.workbench.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * 方法不匹配应返回 405，不能落到 Exception 兜底分支被误报成 500。
 */
class GlobalExceptionHandlerMethodNotAllowedTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void wrongMethodReturnsMethodNotAllowed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/eval/gate");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<?> result = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST")), request, response);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, result.getStatusCode());
        ApiErrorResponse body = (ApiErrorResponse) result.getBody();
        assertNotNull(body);
        assertEquals("请求方法不支持", body.error());
    }

    @Test
    void wrongMethodOnEventStreamStillWritesErrorEvent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/eval/gate");
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET"), request, response);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("event: error"), body);
        assertTrue(body.contains("method_not_allowed"), body);
    }
}
