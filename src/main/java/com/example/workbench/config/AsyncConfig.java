package com.example.workbench.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        return executor("application", 4, 16, 100);
    }

    @Bean(name = "aiTaskExecutor")
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        return executor("ai", 8, 32, 64);
    }

    @Bean(name = "streamTaskExecutor")
    public ThreadPoolTaskExecutor streamTaskExecutor() {
        return executor("stream", 4, 16, 32);
    }

    @Bean(name = "ocrTaskExecutor")
    public ThreadPoolTaskExecutor ocrTaskExecutor() {
        return executor("ocr", 2, 4, 8);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix + "-");
        // 饱和时由调用线程自行执行,提供背压而非静默丢弃任务。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new MdcTaskDecorator());
        // 优雅关机:等待在途任务完成,最多 30 秒。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
