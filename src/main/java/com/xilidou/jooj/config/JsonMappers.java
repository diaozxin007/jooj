package com.xilidou.jooj.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 项目统一的 Jackson {@link ObjectMapper} 工厂。
 *
 * <p>被 {@link com.xilidou.jooj.http.HttpClientConfiguration#joojObjectMapper()} Bean 复用,
 * 同时非 Spring 场景(测试 / 独立工具)直接静态调用 {@link #newMapper()}。
 *
 * <p>工具类,不带 {@code @Configuration} —— 只提供静态工厂,不参与 Bean 生命周期。
 */
public final class JsonMappers {

    private JsonMappers() {}

    public static ObjectMapper newMapper() {
        return new ObjectMapper()
                // ① 多余字段不报错（Anthropic 加新字段不影响我们）
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // ② null 字段不输出（请求体更干净）
                .setSerializationInclusion(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                // ③ 不输出 \"empty\" 数组/Map（请求体更干净）
                //   注意：sealed interface + record 默认 OK，无需特殊配
                // ④ 调试时美化输出（生产环境可关掉，省 bytes）
                .configure(SerializationFeature.INDENT_OUTPUT, false)
                // ⑤ Java 8 时间类型支持(Instant / LocalDateTime 等)
                //   Session record 有 Instant 字段,没这个会序列化失败
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }
}
