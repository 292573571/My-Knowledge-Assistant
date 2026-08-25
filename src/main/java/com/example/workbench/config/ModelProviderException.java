package com.example.workbench.config;

/**
 * 模型服务商返回的内容级错误（HTTP 200 但响应体实为错误报文）或调用异常，
 * 统一转换为面向用户的中文提示，避免把厂商原始错误码（如 6004 / rate limit）直接甩给用户。
 */
public class ModelProviderException extends RuntimeException {

    private final String errorCode;
    private final String userMessage;
    private final String traceId;
    private final int httpStatus;
    private final boolean retryable;

    public ModelProviderException(String errorCode, String userMessage, String traceId,
                                 int httpStatus, boolean retryable) {
        super(userMessage);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
        this.traceId = traceId;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    /** 请求被限流：提示稍后重试或切换模型。 */
    public static ModelProviderException rateLimited(String traceId) {
        return new ModelProviderException("rate_limited",
                "模型服务当前请求过于频繁，请稍候片刻再试，或到「模型配置」切换其他对话模型。",
                traceId, 429, true);
    }

    /** 额度/配额耗尽：提示更换模型或联系管理员。 */
    public static ModelProviderException quotaExceeded(String traceId) {
        return new ModelProviderException("quota_exceeded",
                "模型服务额度已用尽，请更换对话模型或联系管理员。",
                traceId, 429, true);
    }

    /** 鉴权失败：提示检查 API Key。 */
    public static ModelProviderException authError(String traceId) {
        return new ModelProviderException("auth_error",
                "模型服务鉴权失败，请到「模型配置」检查对话模型的 API Key 是否正确。",
                traceId, 401, false);
    }

    /** 通用厂商错误：保留原始错误码便于排查，提示稍后重试或切换模型。 */
    public static ModelProviderException providerError(String code, String rawError, String traceId) {
        String codePart = (code != null && !code.isBlank()) ? "（错误码 " + code + "）" : "";
        String message = "模型服务暂时不可用" + codePart
                + "，请稍后重试或到「模型配置」切换其他对话模型。";
        int status = rawError != null && rawError.toLowerCase().contains("timeout") ? 504 : 502;
        return new ModelProviderException(
                code == null || code.isBlank() ? "provider_error" : code,
                message, traceId, status, true);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getTraceId() {
        return traceId;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
