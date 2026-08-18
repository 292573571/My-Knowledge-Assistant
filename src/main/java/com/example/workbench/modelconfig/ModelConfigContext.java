package com.example.workbench.modelconfig;

import org.springframework.stereotype.Component;

/** 当前请求用户上下文，用于模型调用时按用户解析模型配置。 */
@Component
public class ModelConfigContext {

    private final ThreadLocal<Long> userId = new ThreadLocal<>();

    public void set(Long userId) {
        this.userId.set(userId);
    }

    public Long get() {
        return userId.get();
    }

    public void clear() {
        userId.remove();
    }
}
