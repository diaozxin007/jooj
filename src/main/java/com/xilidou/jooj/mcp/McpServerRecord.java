package com.xilidou.jooj.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MCP server 运行时记录 —— 静态配置(yml/JSON)+ 运行时状态(status/lastError)的统一 record。
 *
 * <p>持久化到 {@code ~/.jooj/mcp-servers/<name>.json},运行时字段(status / lastError /
 * lastConnectedAt)也一并写盘,便于重启后 UI 显示"上次连接失败原因"。
 *
 * <h3>不变式</h3>
 *
 * <ul>
 *   <li>{@code name} 必须非空,且是文件系统合法路径片段(不含 {@code /})</li>
 *   <li>{@code args} / {@code env} 存储为不可变副本({@code List.copyOf} / {@code Map.copyOf})</li>
 *   <li>{@code lastConnectedAt} 只在 {@link Status#CONNECTED} 时更新,FAILED 时保留上次成功时间
 *       —— UI 可以显示"最近一次成功于 X 前,现在 FAILED"</li>
 * </ul>
 *
 * <h3>历史</h3>
 *
 * <p>M1 (2026-07-14):从 {@link McpProperties.Server} 的 yml 平铺结构升级为独立 record。
 */
public record McpServerRecord(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        boolean enabled,
        Status status,
        /** 最后一次失败原因,只在 {@link Status#FAILED} 时有值。 */
        String lastError,
        Instant addedAt,
        /** 最后一次成功 connect 的时刻,可为 null(NEVER_CONNECTED)。 */
        Instant lastConnectedAt) {

    public enum Status {
        NEVER_CONNECTED,
        CONNECTED,
        FAILED,
        DISABLED
    }

    /**
     * Jackson 反序列化用的构造器 —— 显式标注每个参数,防止字段顺序变化时 JSON 读旧文件失败。
     * 同时把 args / env / status 的 null 值兜底为空集合 / NEVER_CONNECTED,让老版本 JSON
     * 无缝升级。
     */
    @JsonCreator
    public McpServerRecord(
            @JsonProperty("name") String name,
            @JsonProperty("command") String command,
            @JsonProperty("args") List<String> args,
            @JsonProperty("env") Map<String, String> env,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("status") Status status,
            @JsonProperty("lastError") String lastError,
            @JsonProperty("addedAt") Instant addedAt,
            @JsonProperty("lastConnectedAt") Instant lastConnectedAt) {
        this.name = name;
        this.command = command;
        this.args = args == null ? List.of() : List.copyOf(args);
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.enabled = enabled;
        this.status = status == null ? Status.NEVER_CONNECTED : status;
        this.lastError = lastError;
        this.addedAt = addedAt;
        this.lastConnectedAt = lastConnectedAt;
    }

    /**
     * 从 yml seed 时的初始状态 —— {@code enabled=true}、{@code status=NEVER_CONNECTED}、
     * {@code addedAt=Instant.now()}。
     */
    public static McpServerRecord fromYml(String name, McpProperties.Server s) {
        return new McpServerRecord(
                name,
                s.getCommand(),
                s.getArgs() == null ? List.of() : List.copyOf(s.getArgs()),
                s.getEnv() == null ? Map.of() : Map.copyOf(s.getEnv()),
                true,
                Status.NEVER_CONNECTED,
                null,
                Instant.now(),
                null);
    }

    /**
     * 生成一个新的记录,仅更新 status / lastError / lastConnectedAt。
     *
     * <p>{@code newStatus == CONNECTED} 时刷新 {@code lastConnectedAt} 为现在;其他情况保留
     * 上次成功时刻,便于 UI 显示"最近一次成功于 X 前"。
     *
     * @param newStatus 新状态
     * @param error 错误信息(仅 FAILED 有意义,其他状态传 null)
     */
    public McpServerRecord withStatus(Status newStatus, String error) {
        return new McpServerRecord(
                name, command, args, env, enabled,
                newStatus, error, addedAt,
                newStatus == Status.CONNECTED ? Instant.now() : lastConnectedAt);
    }
}
