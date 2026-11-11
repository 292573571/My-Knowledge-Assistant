package com.example.workbench.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boot 4 起 MVC 切换到 Jackson 3（{@code tools.jackson}），框架不再为 Jackson 2
 * （{@code com.fasterxml.jackson}）自动装配 {@link ObjectMapper} bean；而业务侧
 * 23 个组件仍注入 Jackson 2 mapper（预研 §6「Jackson 2 与 3 共存」项，全量迁移
 * 到 3 属于独立 Phase 3，不在本次升级范围）。
 *
 * <p>本配置补上 Jackson 2 bean，语义复刻 Boot 3.4 自动配置的等价行为：</p>
 * <ul>
 *   <li>{@code findAndAddModules}：自动注册 classpath 上的 jsr310 / jdk8 模块
 *       （LocalDateTime/Instant 等 java.time 类型可序列化）；</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} 关闭（Boot 默认，反序列化容忍未知字段）；</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS} 关闭（Boot 默认，日期输出 ISO-8601）。</li>
 * </ul>
 *
 * <p>注意：该 bean 只服务业务组件的手工 {@code readValue/writeValueAsString}；
 * REST 响应序列化由 MVC 的 Jackson 3 承担，二者字段名一致但字段顺序（字母序）
 * 与日期格式可能不同，消费方均按字段名解析，不受影响。</p>
 */
@Configuration
public class Jackson2CompatConfig {

    @Bean
    ObjectMapper jackson2ObjectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
