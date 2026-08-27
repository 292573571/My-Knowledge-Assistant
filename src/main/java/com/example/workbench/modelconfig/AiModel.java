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
@Table(name = "ai_models")
@Comment("全局大模型池")
public class AiModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("模型主键")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_type", nullable = false, length = 16)
    @Comment("模型类型：CHAT / EMBEDDING")
    private AiModelType modelType = AiModelType.CHAT;

    @Column(name = "name", nullable = false, length = 64)
    @Comment("模型显示名称")
    private String name;

    @Column(name = "base_url", nullable = false, length = 256)
    @Comment("API 地址")
    private String baseUrl;

    @Column(name = "api_key", nullable = false)
    @Convert(converter = EncryptedStringConverter.class)
    @Comment("API 密钥(透明加密存储)")
    private String apiKey;

    @Column(name = "model", nullable = false, length = 128)
    @Comment("模型标识")
    private String model;

    @Column(name = "temperature")
    @Comment("温度，留空使用全局默认")
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

    @Column(name = "is_default", nullable = false)
    @Comment("是否默认模型")
    private boolean isDefault;

    @Column(name = "enabled", nullable = false)
    @Comment("是否启用")
    private boolean enabled = true;

    @Column(name = "owner_public_id", length = 32)
    @Comment("创建者对外公开标识；为空表示系统模型，仅超级管理员可管理")
    private String ownerPublicId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("创建时间")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Comment("更新时间")
    private Instant updatedAt = Instant.now();

    protected AiModel() {
    }

    public AiModel(String name, String baseUrl, String apiKey, String model) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public Long getId() { return id; }
    public AiModelType getModelType() { return modelType; }
    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public Double getTemperature() { return temperature; }
    public Double getTopP() { return topP; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public Long getRequestTimeoutMs() { return requestTimeoutMs; }
    public String getFallbackModels() { return fallbackModels; }
    public boolean isDefault() { return isDefault; }
    public boolean isEnabled() { return enabled; }
    public String getOwnerPublicId() { return ownerPublicId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String name, String baseUrl, String apiKey, String model, AiModelType modelType,
                       Double temperature, Double topP, Integer maxOutputTokens, Long requestTimeoutMs,
                       String fallbackModels) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.modelType = modelType;
        this.temperature = temperature;
        this.topP = topP;
        this.maxOutputTokens = maxOutputTokens;
        this.requestTimeoutMs = requestTimeoutMs;
        this.fallbackModels = fallbackModels;
        this.updatedAt = Instant.now();
    }

    public void markDefault(boolean isDefault) {
        this.isDefault = isDefault;
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public void setOwnerPublicId(String ownerPublicId) {
        this.ownerPublicId = ownerPublicId;
    }
}
