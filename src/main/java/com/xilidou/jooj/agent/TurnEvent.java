package com.xilidou.jooj.agent;

import java.time.Instant;

/**
 * s22 D-11:一条"turn 期间发生了什么"的事件,给前端 loading 气泡显示进度用。
 *
 * <p>用户等 turn 60s 期间要能看到 agent 在做什么,后端 log 太远 —— agent loop 在 tool 循环
 * 里 push 一个 TurnEvent 到 {@link TurnEventStream},前端 poll {@code /api/chat/{sid}/events}
 * 拿到实时更新 loading 气泡("正在: $ mvn test")。
 *
 * <h3>字段</h3>
 *
 * <ul>
 *   <li>{@code seq} —— 单调递增序号,前端 poll 时传 {@code ?since=<seq>} 拿增量</li>
 *   <li>{@code at} —— 时间戳(server 侧,给前端显示"3s 前")</li>
 *   <li>{@code type} —— 事件类型 slug,前端按 type 选图标/颜色。目前只有 {@code "tool_start"};
 *       未来可加 {@code "assistant_text"}/{@code "compact"}/{@code "recovery"} 等</li>
 *   <li>{@code summary} —— 一行摘要,由 {@link com.xilidou.jooj.tool.Tool#summary} 生成
 *       (如 {@code "$ rm -rf build"});60 字左右</li>
 * </ul>
 *
 * <h3>为什么 record 不 sealed 类型</h3>
 *
 * <p>D-11-a 阶段只推 tool_start 一种事件,前端也只显示 summary 字符串,不做类型区分。
 * 未来加事件类型时再看是否 sealed —— 目前保持简单,前端 by-{@code type} 字符串分派即可。
 *
 * @param seq     单调递增序号(per-sid,由 TurnEventStream 分配)
 * @param at      挂到 stream 时的时间戳
 * @param type    事件类型 slug(如 {@code "tool_start"})
 * @param summary 一行人类可读摘要
 */
public record TurnEvent(
        long seq,
        Instant at,
        String type,
        String summary
) {

    /** 工厂:tool_start 事件。seq 由 {@link TurnEventStream#push} 分配,此处传 0 占位。 */
    public static TurnEvent toolStart(String summary) {
        return new TurnEvent(0L, Instant.now(), "tool_start", summary);
    }
}
