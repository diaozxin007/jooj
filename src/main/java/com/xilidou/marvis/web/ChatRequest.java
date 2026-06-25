package com.xilidou.marvis.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/chat 的请求体。
 *
 * <p>{@code query} 即用户在前端 textarea 输入的原始文本。后端会包装成
 * {@link com.xilidou.marvis.http.dto.MessageParam#user(String)} 喂给 agent loop。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    /** 用户输入的 query 原文,不能为空。 */
    private String query;
}
