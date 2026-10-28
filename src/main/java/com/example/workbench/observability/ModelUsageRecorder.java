package com.example.workbench.observability;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型调用用量采集器：单线程异步落库，任何失败都不影响主链路。
 */
@Component
public class ModelUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ModelUsageRecorder.class);

    private final ModelUsageEventRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "model-usage-recorder");
        thread.setDaemon(true);
        return thread;
    });

    public ModelUsageRecorder(ModelUsageEventRepository repository) {
        this.repository = repository;
    }

    public void record(Long userId, String conversationId, String model, String mode,
                       Integer promptTokens, Integer completionTokens, Integer totalTokens,
                       long durationMs, boolean success) {
        try {
            executor.execute(() -> persist(userId, conversationId, model, mode,
                    promptTokens, completionTokens, totalTokens, durationMs, success));
        } catch (RejectedExecutionException exception) {
            log.warn("Model usage record rejected model={} mode={}", model, mode);
        }
    }

    private void persist(Long userId, String conversationId, String model, String mode,
                         Integer promptTokens, Integer completionTokens, Integer totalTokens,
                         long durationMs, boolean success) {
        try {
            ModelUsageEvent event = new ModelUsageEvent();
            event.setUserId(userId);
            event.setConversationId(conversationId);
            event.setModel(model);
            event.setMode(mode);
            event.setPromptTokens(normalize(promptTokens));
            event.setCompletionTokens(normalize(completionTokens));
            int prompt = event.getPromptTokens();
            int completion = event.getCompletionTokens();
            int total = normalize(totalTokens);
            if (total == 0 && (prompt > 0 || completion > 0)) {
                total = prompt + completion;
            }
            event.setTotalTokens(total);
            event.setDurationMs(Math.max(0, durationMs));
            event.setSuccess(success);
            event.setCreatedAt(java.time.Instant.now());
            repository.save(event);
        } catch (RuntimeException exception) {
            log.warn("Model usage record failed model={} mode={} errorType={}",
                    model, mode, exception.getClass().getSimpleName());
        }
    }

    private static int normalize(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }
}
