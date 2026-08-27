package com.example.workbench.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis 的流式缓冲后端。
 *
 * <p>键设计(均带 TTL,到期自动回收,不依赖应用侧清理):</p>
 * <ul>
 *   <li>{@code shihai:stream:{id}:seq} — String,{@code INCR} 原子分配片段序号</li>
 *   <li>{@code shihai:stream:{id}:chunks} — ZSet,{@code score = seq},member 为片段 JSON,天然有序</li>
 *   <li>{@code shihai:stream:{id}:meta} — Hash,保存 {@code status} 与 {@code terminalSeq}</li>
 * </ul>
 *
 * <p>跨实例实时性由 Pub/Sub 频道 {@code shihai:stream:events} 保证:写入方发布 streamId,
 * 其他实例收到后从 ZSet 拉取增量并投递给本地订阅者。因此即使「生成」与「推送」落在不同实例上,
 * 客户端依然能连续收到 token。</p>
 *
 * <p>所有 Redis 操作都做了异常兜底:单次操作失败只记录日志,不让流式回答整体崩掉。</p>
 */
public class RedisStreamBufferBackend implements StreamBufferBackend {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamBufferBackend.class);
    private static final String KEY_PREFIX = "shihai:stream:";
    public static final String EVENT_CHANNEL = "shihai:stream:events";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final List<Consumer<String>> remoteListeners = new CopyOnWriteArrayList<>();
    /** 本实例自己发布的通知会回环回来,靠它过滤掉,避免无谓的重复拉取。 */
    private final String instanceId = java.util.UUID.randomUUID().toString();

    public RedisStreamBufferBackend(StringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public long nextSeq(String streamId) {
        Long seq = redis.opsForValue().increment(seqKey(streamId));
        if (seq == null) {
            throw new IllegalStateException("Redis 未能分配流式片段序号 streamId=" + streamId);
        }
        redis.expire(seqKey(streamId), ttl);
        return seq;
    }

    @Override
    public void appendChunk(String streamId, StreamChunk chunk) {
        try {
            redis.opsForZSet().add(chunksKey(streamId), serialize(chunk), chunk.seq());
            redis.expire(chunksKey(streamId), ttl);
            publish(streamId);
        } catch (RuntimeException exception) {
            log.warn("流式片段写入 Redis 失败 streamId={} seq={} error={}",
                    streamId, chunk.seq(), exception.getMessage());
        }
    }

    @Override
    public List<StreamChunk> readChunks(String streamId, long fromSeq) {
        try {
            // ZSet 按 score 升序返回,(fromSeq 为开区间下界,避免重复投递已送出的片段。
            Set<String> raw = redis.opsForZSet()
                    .rangeByScore(chunksKey(streamId), fromSeq + 1e-9, Double.MAX_VALUE);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<StreamChunk> out = new ArrayList<>(raw.size());
            for (String item : raw) {
                StreamChunk chunk = deserialize(item);
                if (chunk != null && chunk.seq() > fromSeq) {
                    out.add(chunk);
                }
            }
            out.sort((left, right) -> Long.compare(left.seq(), right.seq()));
            return out;
        } catch (RuntimeException exception) {
            log.warn("流式片段读取 Redis 失败 streamId={} fromSeq={} error={}",
                    streamId, fromSeq, exception.getMessage());
            return List.of();
        }
    }

    @Override
    public void createSession(String streamId, Long userId) {
        try {
            redis.delete(List.of(seqKey(streamId), chunksKey(streamId), metaKey(streamId)));
            redis.opsForHash().putAll(metaKey(streamId), Map.of(
                    "status", StreamSession.Status.RUNNING.name(),
                    "terminalSeq", "0",
                    "userId", userId == null ? "" : userId.toString()));
            redis.expire(metaKey(streamId), ttl);
        } catch (RuntimeException exception) {
            log.warn("流式会话初始化 Redis 失败 streamId={} error={}", streamId, exception.getMessage());
        }
    }

    @Override
    public void saveTerminal(String streamId, StreamSession.Status status, StreamChunk terminal) {
        try {
            redis.opsForHash().putAll(metaKey(streamId), Map.of(
                    "status", status.name(),
                    "terminalSeq", String.valueOf(terminal == null ? 0L : terminal.seq())));
            redis.expire(metaKey(streamId), ttl);
            publish(streamId);
        } catch (RuntimeException exception) {
            log.warn("流式会话终态写入 Redis 失败 streamId={} status={} error={}",
                    streamId, status, exception.getMessage());
        }
    }

    @Override
    public SessionState readState(String streamId) {
        try {
            Object status = redis.opsForHash().get(metaKey(streamId), "status");
            if (status == null) {
                return null;
            }
            Object terminalSeq = redis.opsForHash().get(metaKey(streamId), "terminalSeq");
            Object userId = redis.opsForHash().get(metaKey(streamId), "userId");
            return new SessionState(StreamSession.Status.valueOf(status.toString()), parseLong(terminalSeq), parseLongOrNull(userId));
        } catch (RuntimeException exception) {
            log.warn("流式会话状态读取 Redis 失败 streamId={} error={}", streamId, exception.getMessage());
            return null;
        }
    }

    @Override
    public void remove(String streamId) {
        try {
            redis.delete(List.of(seqKey(streamId), chunksKey(streamId), metaKey(streamId)));
        } catch (RuntimeException exception) {
            log.warn("流式会话清理 Redis 失败 streamId={} error={}", streamId, exception.getMessage());
        }
    }

    @Override
    public void onRemoteAppend(Consumer<String> listener) {
        remoteListeners.add(listener);
    }

    /** 供 {@code RedisMessageListenerContainer} 注册的监听器:把远端通知转成本地拉取。 */
    public MessageListener listener() {
        return new MessageListener() {
            @Override
            public void onMessage(Message message, byte[] pattern) {
                String payload = new String(message.getBody(), StandardCharsets.UTF_8);
                int separator = payload.indexOf('|');
                if (separator <= 0) {
                    return;
                }
                String origin = payload.substring(0, separator);
                if (instanceId.equals(origin)) {
                    return; // 自己发的通知,本地已直接投递
                }
                String streamId = payload.substring(separator + 1);
                for (Consumer<String> listener : remoteListeners) {
                    try {
                        listener.accept(streamId);
                    } catch (RuntimeException exception) {
                        log.warn("处理远端流式通知失败 streamId={} error={}", streamId, exception.getMessage());
                    }
                }
            }
        };
    }

    private void publish(String streamId) {
        try {
            redis.convertAndSend(EVENT_CHANNEL, instanceId + "|" + streamId);
        } catch (RuntimeException exception) {
            log.debug("流式通知发布失败 streamId={} error={}", streamId, exception.getMessage());
        }
    }

    private String serialize(StreamChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化流式片段 seq=" + chunk.seq(), exception);
        }
    }

    private StreamChunk deserialize(String raw) {
        try {
            return objectMapper.readValue(raw, StreamChunk.class);
        } catch (Exception exception) {
            log.warn("无法反序列化流式片段,已跳过 error={}", exception.getMessage());
            return null;
        }
    }

    private static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static Long parseLongOrNull(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String seqKey(String streamId) {
        return KEY_PREFIX + streamId + ":seq";
    }

    private static String chunksKey(String streamId) {
        return KEY_PREFIX + streamId + ":chunks";
    }

    private static String metaKey(String streamId) {
        return KEY_PREFIX + streamId + ":meta";
    }
}
