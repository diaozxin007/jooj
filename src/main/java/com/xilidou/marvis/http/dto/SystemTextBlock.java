package com.xilidou.marvis.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SYSTEM 片段 text block,允许带 {@link CacheControl}。
 *
 * <p>对应 Anthropic 协议里 system 字段的 array 形态:
 * <pre>
 *   "system": [
 *     {
 *       "type": "text",
 *       "text": "...",
 *       "cache_control": { "type": "ephemeral" }
 *     },
 *     {
 *       "type": "text",
 *       "text": "...动态部分..."
 *     }
 *   ]
 * </pre>
 *
 * <p>{@code cache_control} 字段加在哪一块的末尾,缓存就到哪里 —— 服务器从请求
 * 开头到该标记之间的所有 token 作为前缀,统一计算 hash 当作 cache key。
 *
 * <p>marvis 的用法:第 1 块装 identity + tools + workspace(稳定),加
 * cache_control;第 2 块装 memory(易变),不加。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemTextBlock {

    /** 类型常量,固定为 {@code "text"}。 */
    @Builder.Default
    private String type = "text";

    /** 这一段 SYSTEM 的实际内容。 */
    private String text;

    /** 缓存标记,可空。空 = 这一段不参与缓存边界。 */
    @JsonProperty("cache_control")
    private CacheControl cacheControl;
}
