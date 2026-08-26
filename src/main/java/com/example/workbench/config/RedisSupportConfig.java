package com.example.workbench.config;

import com.example.workbench.modelconfig.CircuitBreakerStateStore;
import com.example.workbench.modelconfig.MemoryCircuitBreakerStateStore;
import com.example.workbench.modelconfig.RedisCircuitBreakerStateStore;
import com.example.workbench.streaming.MemoryStreamBufferBackend;
import com.example.workbench.streaming.RedisStreamBufferBackend;
import com.example.workbench.streaming.StreamBufferBackend;
import com.example.workbench.streaming.StreamBufferProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 能力装配与自动降级。
 *
 * <p>Redis 在本项目中只承载**可重建的易失数据**:流式回答的片段缓冲(支持断点续传)与模型熔断状态
 * (多实例共享健康判断)。因此 Redis 不可用绝不能让应用启动失败或让功能不可用 ——
 * 本配置在启动时主动探活,失败则回落到进程内实现并明确记录日志。</p>
 *
 * <p>本地开发与单元测试无需安装 Redis:探活失败会自动走进程内实现,行为与改造前一致。</p>
 */
@Configuration
public class RedisSupportConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisSupportConfig.class);

    /**
     * 探活:能否真正拿到连接并 PING 通。
     *
     * <p>Lettuce 是懒连接的,仅拿到 {@code RedisConnectionFactory} 并不代表 Redis 可用,
     * 必须实际建连一次才能判断。</p>
     */
    private boolean reachable(RedisConnectionFactory factory) {
        if (factory == null) {
            return false;
        }
        try (RedisConnection connection = factory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException exception) {
            log.debug("Redis 探活失败 error={}", exception.getMessage());
            return false;
        }
    }

    private boolean decideRedis(String feature, boolean forceMemory, boolean requireRedis,
                                RedisConnectionFactory factory) {
        if (forceMemory) {
            log.info("{} 使用进程内实现(配置强制 memory)", feature);
            return false;
        }
        if (reachable(factory)) {
            return true;
        }
        if (requireRedis) {
            log.error("{} 配置要求使用 Redis,但 Redis 不可达,已降级为进程内实现。"
                    + "请检查 REDIS_HOST/REDIS_PORT/REDIS_PASSWORD 与 redis 服务状态。", feature);
        } else {
            log.warn("{} 未检测到可用的 Redis,已降级为进程内实现"
                    + "(单实例可正常工作,但进程重启后缓冲会丢失)", feature);
        }
        return false;
    }

    @Bean
    public StreamBufferBackend streamBufferBackend(StreamBufferProperties properties,
                                                   ObjectProvider<StringRedisTemplate> redisTemplate,
                                                   ObjectProvider<RedisConnectionFactory> connectionFactory,
                                                   ObjectMapper objectMapper) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        boolean useRedis = template != null && decideRedis("流式缓冲", properties.forceMemory(),
                properties.requireRedis(), connectionFactory.getIfAvailable());
        if (!useRedis) {
            return new MemoryStreamBufferBackend();
        }
        // TTL 稍长于会话保留期,给「刚过期就重连」留出余量。
        Duration ttl = Duration.ofSeconds(properties.ttlSeconds() + 60);
        log.info("流式缓冲使用 Redis backend ttl={}s", ttl.toSeconds());
        return new RedisStreamBufferBackend(template, objectMapper, ttl);
    }

    /** 订阅跨实例流式通知频道;仅在真正使用 Redis 后端时注册容器。 */
    @Bean
    public RedisMessageListenerContainer streamEventListenerContainer(
            StreamBufferBackend backend,
            ObjectProvider<RedisConnectionFactory> connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (!(backend instanceof RedisStreamBufferBackend redisBackend) || factory == null) {
            return container; // 未启动的空容器,不建立任何连接
        }
        container.setConnectionFactory(factory);
        container.addMessageListener(redisBackend.listener(),
                new ChannelTopic(RedisStreamBufferBackend.EVENT_CHANNEL));
        log.info("已订阅跨实例流式通知频道 channel={}", RedisStreamBufferBackend.EVENT_CHANNEL);
        return container;
    }

    @Bean
    public CircuitBreakerStateStore circuitBreakerStateStore(
            @Value("${app.ai.circuit-breaker.state-backend:auto}") String stateBackend,
            @Value("${app.ai.circuit-breaker.cooldown-ms:30000}") long cooldownMs,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            ObjectProvider<RedisConnectionFactory> connectionFactory) {
        String mode = stateBackend == null ? "auto" : stateBackend.trim();
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        boolean useRedis = template != null && decideRedis("模型熔断状态",
                "memory".equalsIgnoreCase(mode), "redis".equalsIgnoreCase(mode),
                connectionFactory.getIfAvailable());
        if (!useRedis) {
            return new MemoryCircuitBreakerStateStore();
        }
        // 熔断状态在冷却期数倍时长后自然失效,避免模型下线后残留脏状态。
        Duration ttl = Duration.ofMillis(Math.max(60_000L, cooldownMs * 10));
        log.info("模型熔断状态使用 Redis 共享 ttl={}s", ttl.toSeconds());
        return new RedisCircuitBreakerStateStore(template, ttl);
    }
}
