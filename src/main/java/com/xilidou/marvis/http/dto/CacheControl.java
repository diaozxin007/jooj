package com.xilidou.marvis.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anthropic prompt cache 控制标记。
 *
 * <p>对应 JSON:
 * <pre>
 *   { "type": "ephemeral" }              // 5 分钟 TTL,默认
 *   { "type": "ephemeral", "ttl": "1h" } // 1 小时 TTL
 * </pre>
 *
 * <p>放在 system text block / tool definition / message content block 上,
 * 表示"缓存到此为止"。Anthropic 服务器内部:
 * <ol>
 *   <li>对前缀(从请求开头到这个标记位置的所有 token)算 hash → cache key</li>
 *   <li>查 KV 存储:命中 → 跳过 prefill,按 0.1× 计费;未命中 → 完整 prefill 并写入,按 1.25× 计费</li>
 *   <li>5 分钟 TTL,过期清掉</li>
 * </ol>
 *
 * <p>命中要求(也是为什么要把"会变的"和"不变的"拆开):
 * <ul>
 *   <li>请求开头到 cache_control 之间 byte 一字未变</li>
 *   <li>这段长度 ≥ 模型阈值(Sonnet 4.6: 2048 token / Opus 4.6+: 4096 token);
 *       低于阈值 Anthropic **静默忽略** cache_control,不报错但 cache_creation_input_tokens 永远是 0</li>
 *   <li>同 model id;model 切换 cache 失效</li>
 * </ul>
 *
 * <p>marvis 的用法:在 system 段第 1 个 text block 末尾标 ephemeral,
 * 让 identity + tools + workspace 这段稳定内容被缓存;memory 单独放第 2 个
 * text block 不标,memory 写入时只有第 2 段失效,第 1 段仍命中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CacheControl {

    /** 缓存类型,目前 Anthropic 只支持 {@code "ephemeral"}。 */
    private String type;

    /**
     * TTL,可选。允许值:{@code "5m"}(默认)/ {@code "1h"}。
     *
     * <p>5 分钟适合连续对话(第 2 次请求即回本);1 小时适合突发流量、长间隔批处理
     * (写入开销 2×,需要 ≥ 3 次请求才回本)。
     */
    private String ttl;

    /** 便利工厂:5 分钟 ephemeral 缓存。 */
    public static CacheControl ephemeral() {
        return CacheControl.builder().type("ephemeral").build();
    }

    /** 便利工厂:1 小时 ephemeral 缓存。 */
    public static CacheControl ephemeral1h() {
        return CacheControl.builder().type("ephemeral").ttl("1h").build();
    }
}
