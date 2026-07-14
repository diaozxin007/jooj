package com.xilidou.jooj.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP 注册表 —— 维护已连接的 {@link McpClient} 集合。
 *
 * <p>对应上游 s19 的 {@code mcp_clients: dict[str, MCPClient] = {}} 全局变量。
 *
 * <h3>职责</h3>
 *
 * <ul>
 *   <li>{@link #connect} —— LLM 调 {@code connect_mcp(server_name)} 时被触发,
 *       走 transport.listTools 发现工具,缓存为 McpClient 对象</li>
 *   <li>{@link #findToolByPrefixedName} —— McpProxyTool dispatch 时,
 *       根据 {@code mcp__server__tool} 前缀名找回 (client, originalToolName)</li>
 *   <li>{@link #allClients} —— McpProxyTool 列举工具时遍历用</li>
 *   <li>{@link #normalizeName} —— 工具名规范化,跟上游 {@code [^a-zA-Z0-9_-]} 替换 _ 一致</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 *
 * <p>{@code connectedClients} 用 {@link Map} + 简单同步,因为 connect 在 Lead 主路径
 * 单线程发生(REPL / Web 请求都被 agentLock 保护),没有真并发场景。
 */
@Component
@Slf4j
public class McpRegistry {

    /** 上游 {@code [^a-zA-Z0-9_-]} 替换为 _。 */
    private static final Pattern DISALLOWED = Pattern.compile("[^a-zA-Z0-9_-]");

    /** 工具名前缀。跟上游 {@code mcp__<server>__<tool>} 严格一致。 */
    public static final String PREFIX = "mcp__";

    private final McpTransport transport;
    private final McpServerRegistry serverRegistry;
    private final Map<String, McpClient> connectedClients = new LinkedHashMap<>();

    public McpRegistry(McpTransport transport, McpServerRegistry serverRegistry) {
        this.transport = transport;
        this.serverRegistry = serverRegistry;
    }

    // ─────────────────────────────────────────────────────────────
    //  connect / disconnect / list
    // ─────────────────────────────────────────────────────────────

    /**
     * 连接一个 MCP server,发现工具并缓存。
     *
     * <p>对应上游 {@code connect_mcp(name)}:
     * <ol>
     *   <li>已连过 → 返回 "already connected" 字符串(LLM 接受为成功)</li>
     *   <li>server 不存在 → 列出可用 servers</li>
     *   <li>调 transport.listTools 发现工具,生成 McpClient,缓存</li>
     * </ol>
     *
     * <h3>M1 (2026-07-14) 行为变更</h3>
     *
     * <p>listTools 抛出的异常现在被 catch,同步做两件事:
     * <ul>
     *   <li>调 {@link McpServerRegistry#markFailed} 更新 status = FAILED + lastError 落盘</li>
     *   <li>返回 "Failed to connect ..." 错误字符串给 LLM(而不是抛给 caller)</li>
     * </ul>
     *
     * <p>之前的行为是让异常传给 caller —— LLM 看到的是 SDK 栈迹而不是友好错误。M1 后统一化。
     * mock server 走这条路径也一样 catch(mock 不太会抛,但兜底更安全)。
     *
     * @return 成功 / 失败的人类可读字符串
     */
    public synchronized String connect(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return "Error: server name must not be blank";
        }
        if (connectedClients.containsKey(serverName)) {
            return "MCP server '" + serverName + "' already connected";
        }
        if (!transport.serverExists(serverName)) {
            return "Unknown server '" + serverName + "'. Available: " +
                    String.join(", ", transport.availableServers());
        }
        try {
            List<McpToolDef> tools = transport.listTools(serverName);
            McpClient client = new McpClient(serverName, transport, tools);
            connectedClients.put(serverName, client);
            // 只有 registry 里有的 server 才 markConnected(mock server 不在 registry)
            if (serverRegistry.contains(serverName)) {
                serverRegistry.markConnected(serverName);
            }

            List<String> toolNames = tools.stream().map(McpToolDef::getName).collect(Collectors.toList());
            log.info("[MCP] connected {} → {}", serverName, toolNames);
            return "Connected to MCP server '" + serverName + "'. Discovered " + tools.size() +
                    " tools: " + String.join(", ", toolNames);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (serverRegistry.contains(serverName)) {
                serverRegistry.markFailed(serverName, msg);
            }
            log.warn("[MCP] connect '{}' failed: {}", serverName, msg);
            return "Failed to connect MCP server '" + serverName + "': " + msg;
        }
    }

    /** 已连接的所有 client(只读拷贝,迭代用)。 */
    public synchronized Collection<McpClient> allClients() {
        return List.copyOf(connectedClients.values());
    }

    /** 测试 / 监控用:已连接 server 数。 */
    public synchronized int connectedCount() {
        return connectedClients.size();
    }

    /** 测试用:清掉所有连接。生产路径不调。 */
    synchronized void clear() {
        connectedClients.clear();
    }

    // ─────────────────────────────────────────────────────────────
    //  prefix 工具名解析
    // ─────────────────────────────────────────────────────────────

    /**
     * 把 server 内部工具名包装成 {@code mcp__<server>__<tool>}(规范化非法字符)。
     *
     * <p>对应上游 {@code f"mcp__{normalize_mcp_name(server)}__{normalize_mcp_name(tool)}"}。
     */
    public static String prefixedName(String serverName, String toolName) {
        return PREFIX + normalizeName(serverName) + "__" + normalizeName(toolName);
    }

    /**
     * 规范化 name —— 把非 {@code [a-zA-Z0-9_-]} 替换为 _。跟上游严格一致。
     */
    public static String normalizeName(String name) {
        if (name == null) return "";
        return DISALLOWED.matcher(name).replaceAll("_");
    }

    /**
     * 根据带前缀的工具名解析回 (client, original toolName)。
     *
     * <p>例如 {@code mcp__docs__search} → (docs client, "search")。
     *
     * <p>因为 normalize 后可能有歧义(原 name 包含 underscore / 替换字符),
     * 通过遍历已连接 client + 各自工具找到第一个 prefixedName 匹配的工具。
     *
     * @return Optional.empty 表示工具不存在
     */
    public synchronized Optional<ResolvedMcpCall> findToolByPrefixedName(String prefixedName) {
        if (prefixedName == null || !prefixedName.startsWith(PREFIX)) return Optional.empty();
        for (McpClient client : connectedClients.values()) {
            for (McpToolDef def : client.getTools()) {
                if (prefixedName.equals(prefixedName(client.getName(), def.getName()))) {
                    return Optional.of(new ResolvedMcpCall(client, def));
                }
            }
        }
        return Optional.empty();
    }

    /** 解析结果 —— McpProxyTool dispatch 时拿来调 callTool。 */
    public record ResolvedMcpCall(McpClient client, McpToolDef toolDef) {
    }
}
