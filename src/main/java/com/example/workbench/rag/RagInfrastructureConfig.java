package com.example.workbench.rag;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 基础设施 Bean。
 *
 * <p>IngestionPathResolver 无共享可变状态；DocumentIngestionService 内部仍持有自己的实例，
 * 这里注册的实例供健康检查等只读服务注入使用，两者语义一致（docs 相对工作目录）。</p>
 */
@Configuration
public class RagInfrastructureConfig {

    @Bean
    IngestionPathResolver ingestionPathResolver() {
        return new IngestionPathResolver(Path.of("docs"));
    }
}
