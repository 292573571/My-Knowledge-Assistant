package com.example.workbench.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Boot 4 删除了 Jackson 2 的 ObjectMapper 自动装配，而业务组件（AuthFilter 等
 * 23 处）仍构造器注入 Jackson 2 mapper——线上启动失败的真实场景（1fb5f73 首次
 * 发版回滚）。本测试是该场景的最小复现：无本配置时 bean 缺失，有本配置时
 * 语义与 Boot 3.4 自动配置等价。
 */
class Jackson2CompatConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Jackson2CompatConfig.class);

    @Test
    void withoutConfigNoJackson2BeanExists() {
        new ApplicationContextRunner().run(context ->
                assertThat(context).doesNotHaveBean(ObjectMapper.class));
    }

    @Test
    void withConfigSingleJackson2BeanAvailableForInjection() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ObjectMapper.class);
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertThat(mapper).isInstanceOf(JsonMapper.class);
        });
    }

    @Test
    void mapperSemanticsMatchBoot34AutoConfiguration() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            // Boot 3.4 默认：反序列化容忍未知字段
            assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
            // Boot 3.4 默认：日期输出 ISO-8601 而非时间戳
            assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
            // jsr310 模块已注册（java.time 序列化可用）
            assertThat(mapper.getRegisteredModuleIds())
                    .contains("jackson-datatype-jsr310");
        });
    }
}
