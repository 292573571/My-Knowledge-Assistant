package com.example.workbench.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 静态资源 404(favicon.ico / robots.txt / 未知路径)是浏览器与爬虫的预期行为,
 * 必须走专用 handler 返回 404,不能落到 Exception 兜底分支刷 ERROR 日志。
 */
class GlobalExceptionHandlerNotFoundTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingStaticResourceReturnsNotFoundInsteadOfServerError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/favicon.ico");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<?> result = handler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.GET, "favicon.ico"), request, response);

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
        ApiErrorResponse body = (ApiErrorResponse) result.getBody();
        assertNotNull(body);
        assertEquals("资源不存在", body.error());
    }

    @Test
    void missingStaticResourceOnEventStreamStillWritesErrorEvent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/robots.txt");
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.GET, "robots.txt"), request, response);

        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("event: error"), body);
        assertTrue(body.contains("resource_not_found"), body);
    }
}
