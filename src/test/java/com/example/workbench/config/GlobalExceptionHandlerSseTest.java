package com.example.workbench.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerSseTest {

    private static final String MESSAGE = "message 不能超过 4000 个字符";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void validationFailureOnEventStreamWritesSseErrorEvent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleValidationException(validationException(), request, response);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        assertTrue(response.getContentType().contains(MediaType.TEXT_EVENT_STREAM_VALUE));
        String body = response.getContentAsString();
        assertTrue(body.contains("event: error"), body);
        assertTrue(body.contains(MESSAGE), body);
    }

    @Test
    void validationFailureOnJsonRequestStillReturnsJsonBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<?> result = handler.handleValidationException(validationException(), request, response);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals(MESSAGE, ((ApiErrorResponse) result.getBody()).error());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void unexpectedFailureOnEventStreamWritesSseErrorEvent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleUnexpectedException(new IllegalStateException("boom"), request, response);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("event: error"), body);
        assertTrue(body.contains("服务暂时不可用"), body);
    }

    private MethodArgumentNotValidException validationException() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "learningAssistantMessageRequest");
        bindingResult.addError(new FieldError("learningAssistantMessageRequest", "message", MESSAGE));
        Method method = GlobalExceptionHandlerSseTest.class.getDeclaredMethod("dummy", String.class);
        return new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);
    }

    private static void dummy(String message) {
    }
}
