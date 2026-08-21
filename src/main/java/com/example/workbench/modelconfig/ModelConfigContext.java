package com.example.workbench.modelconfig;

import org.springframework.stereotype.Component;

/** 当前请求用户上下文，用于模型调用时按用户解析模型配置。 */
@Component
public class ModelConfigContext {

    private final ThreadLocal<Long> userId = new ThreadLocal<>();
    private final ThreadLocal<Long> selectedModelId = new ThreadLocal<>();
    private final ThreadLocal<String> publicId = new ThreadLocal<>();

    public void set(Long userId) {
        this.userId.set(userId);
    }

    public void set(Long userId, Long modelId) {
        this.userId.set(userId);
        if (modelId == null) this.selectedModelId.remove();
        else this.selectedModelId.set(modelId);
    }

    public void set(Long userId, String publicId, Long modelId) {
        set(userId, modelId);
        if (publicId == null) this.publicId.remove();
        else this.publicId.set(publicId);
    }

    public Long get() {
        return userId.get();
    }

    public Long getSelectedModelId() {
        return selectedModelId.get();
    }

    public String getPublicId() {
        return publicId.get();
    }

    public void clear() {
        userId.remove();
        selectedModelId.remove();
        publicId.remove();
    }
}
