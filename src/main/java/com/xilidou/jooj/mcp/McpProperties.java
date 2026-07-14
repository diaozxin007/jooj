package com.xilidou.jooj.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP plugin(s19)真实 SDK 配置 —— 只描述 stdio 子进程类型的 server。
 *
 * <p>每个 server 一个 {@link Server} 子配置:
 * <pre>
 *   jooj:
 *     mcp:
 *       servers:
 *         filesystem:
 *           command: npx
 *           args: ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/notes"]
 *           env: { LOG_LEVEL: "info" }
 *         git:
 *           command: npx
 *           args: ["-y", "@modelcontextprotocol/server-git", "--repo", "."]
 * </pre>
 *
 * <p>路由规则(由 {@link McpRegistry} 实施):
 * <ul>
 *   <li>{@code connect("filesystem")} 时,先看 yml 是否有 {@code servers.filesystem} —— 有就走真实 SDK</li>
 *   <li>没配置则退化到 mock(原 docs / deploy 还能用)</li>
 * </ul>
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Mcp} 拆出,前缀 {@code jooj.mcp} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.mcp")
public class McpProperties {

    /** 启动 stdio 子进程时,等待 server 进入 ready 的超时(毫秒)。默认 30s 给冷启动 npm 拉包余地。 */
    private long startupTimeoutMs = 30_000;

    /** 单次 listTools / callTool 调用超时(毫秒)。默认 60s。 */
    private long callTimeoutMs = 60_000;

    /**
     * server 配置表,key = server name(LLM 在 connect_mcp 工具里用的 name)。
     *
     * <p><b>M1 (2026-07-14) 后新语义</b>:此字段仅作**首次启动的 seed 源**。
     * {@link McpServerRegistry} 构造时调 {@code seedFromYml},把 yml 里的每个 server
     * 落盘为 {@code ~/.jooj/mcp-servers/<name>.json}。之后 source-of-truth 是 JSON 目录,
     * yml 侧的 servers 段不再被 MCP 运行时读取(seed 幂等:同名 JSON 已存在时跳过)。
     *
     * <p>用户想加 / 删 / 改 server 请通过 M3 的 {@code mcp_manage} 工具或 M4 的
     * Web UI —— 直接编辑 yml 不会生效(除非删掉对应的 JSON 触发下一次启动 seed)。
     */
    private Map<String, Server> servers = new LinkedHashMap<>();

    /** 单个 stdio MCP server 配置。 */
    @Data
    public static class Server {
        /** 启动命令(如 {@code npx} / {@code node} / 绝对可执行路径)。 */
        private String command;
        /** 命令参数列表。 */
        private List<String> args = new ArrayList<>();
        /** 子进程环境变量(覆盖父进程同名)。 */
        private Map<String, String> env = new LinkedHashMap<>();
    }
}
