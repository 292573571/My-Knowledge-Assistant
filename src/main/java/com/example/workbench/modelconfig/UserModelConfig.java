package com.example.workbench.modelconfig;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "user_model_config")
@Comment("用户模型配置")
public class UserModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("模型配置主键")
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    @Comment("关联用户主键")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 24)
    @Comment("模型模式：跟随默认、使用池模型、自定义")
    private UserModelMode mode = UserModelMode.FOLLOW_DEFAULT;

    @Column(name = "model_id")
    @Comment("选中模型池中的模型")
    private Long modelId;

    @Column(name = "name", length = 64)
    @Comment("自定义模型名称")
    private String name;

    @Column(name = "base_url", length = 256)
    @Comment("自定义模型接口地址")
    private String baseUrl;

    @Column(name = "api_key")
    @Convert(converter = EncryptedStringConverter.class)
    @Comment("自定义模型 API 密钥(透明加密存储)")
    private String apiKey;

    @Column(name = "model", length = 128)
    @Comment("自定义模型标识")
    private String model;

    @Column(name = "temperature")
    @Comment("温度参数，留空使用全局默认")
    private Double temperature;

    @Column(name = "top_p")
    @Comment("采样参数，留空使用全局默认")
    private Double topP;

    @Column(name = "max_output_tokens")
    @Comment("最大输出 token，留空使用全局默认")
    private Integer maxOutputTokens;

    @Column(name = "request_timeout_ms")
    @Comment("请求超时毫秒，留空使用全局默认")
    private Long requestTimeoutMs;

    @Column(name = "fallback_models", length = 256)
    @Comment("备用模型，逗号分隔")
    private String fallbackModels;

    @Column(name = "updated_at", nullable = false)
    @Comment("更新时间")
    private Instant updatedAt = Instant.now();

    protected UserModelConfig() {
    }

    public UserModelConfig(Long userId) {
        this.userId = userId;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public UserModelMode getMode() { return mode; }
    public Long getModelId() { return modelId; }
    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public Double getTemperature() { return temperature; }
    public Double getTopP() { return topP; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public Long getRequestTimeoutMs() { return requestTimeoutMs; }
    public String getFallbackModels() { return fallbackModels; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void followDefault() {
        this.mode = UserModelMode.FOLLOW_DEFAULT;
        this.modelId = null;
        clearCustomFields();
        this.updatedAt = Instant.now();
    }

    public void usePoolModel(Long modelId) {
        this.mode = UserModelMode.USE_POOL_MODEL;
        this.modelId = modelId;
        clearCustomFields();
        this.updatedAt = Instant.now();
    }

    public void useCustom(String name, String baseUrl, String apiKey, String model, Double temperature,
                          Double topP, Integer maxOutputTokens, Long requestTimeoutMs, String fallbackModels) {
        this.mode = UserModelMode.CUSTOM;
        this.modelId = null;
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxOutputTokens = maxOutputTokens;
        this.requestTimeoutMs = requestTimeoutMs;
        this.fallbackModels = fallbackModels;
        this.updatedAt = Instant.now();
    }

    private void clearCustomFields() {
        this.name = null;
        this.baseUrl = null;
        this.apiKey = null;
        this.model = null;
        this.temperature = null;
        this.topP = null;
        this.maxOutputTokens = null;
        this.requestTimeoutMs = null;
        this.fallbackModels = null;
    }
}
