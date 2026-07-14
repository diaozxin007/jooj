package com.xilidou.jooj.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实 stdio MCP transport —— 用 {@code io.modelcontextprotocol.sdk:mcp:2.0.0} SDK
 * 启动外部 server 子进程,通过 JSON-RPC over stdio 通信。
 *
 * <h3>路由策略</h3>
 *
 * <p>本类不替代 {@link MockMcpTransport},而是<b>叠加</b>:
 * <ul>
 *   <li>{@code yml: jooj.mcp.servers.<name>} 有配置 → 走真实 SDK</li>
 *   <li>没配置 → 委托给 {@link MockMcpTransport}(原 docs/deploy 仍可用)</li>
 * </ul>
 *
 * <p>{@link McpRegistry} 注入这一个 Bean(@Primary 本类),所有路径透明。
 *
 * <h3>子进程生命周期</h3>
 *
 * <ul>
 *   <li><b>Lazy init</b>:首次 {@code listTools(name)} 触发启动 → 缓存 {@link McpSyncClient}</li>
 *   <li><b>{@link PreDestroy}</b>:容器关停时优雅关闭所有 client(下发 SIGTERM 给子进程)</li>
 *   <li>启动失败 → 抛 {@link IllegalStateException},jooj caller 看到 stack trace</li>
 * </ul>
 *
 * <h3>API 适配</h3>
 *
 * <p>SDK 的 {@code McpSchema.Tool / CallToolResult} 跟 jooj 的
 * {@link McpToolDef / String output} 字段不一致,本类做转换:
 * <ul>
 *   <li>{@code Tool.name() / description() / inputSchema()} → {@link McpToolDef}</li>
 *   <li>{@code CallToolResult.content()} 取第一个 {@code TextContent.text()};
 *       全空时返 "(no text content)";{@code isError=true} 时前缀 {@code "MCP error: "}</li>
 * </ul>
 *
 * <h3>实测过的 SDK 坑</h3>
 *
 * <ul>
 *   <li>{@code StdioClientTransport} 构造器要求 {@code (ServerParameters, McpJsonMapper)},
 *       文档示例的单参形式过时</li>
 *   <li>{@code CallToolResult.isError} 可能是 {@code null}(MCP spec 在 success 时不传),
 *       要用 {@code Boolean.TRUE.equals(...)} 判断,不能直接 unbox</li>
 *   <li>第一次 {@code npx -y} 拉包很慢(几秒~几十秒),需要给 startup 充足超时</li>
 * </ul>
 */
@Component
@Primary
@Slf4j
public class SdkStdioMcpTransport implements McpTransport {

    private final McpProperties config;
    private final MockMcpTransport mockFallback;

    /** server name → 已初始化的 SDK client。lazy init,首次访问时启动。 */
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public SdkStdioMcpTransport(McpProperties config,
                                ObjectProvider<MockMcpTransport> mockProvider) {
        this.config = config;
        // mock 是可选 fallback —— 没装 mock Bean 也能跑(比如生产 profile 关掉 mock)
        this.mockFallback = mockProvider.getIfAvailable();
    }

    // ─────────────────────────────────────────────────────────────
    //  McpTransport 接口
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<McpToolDef> listTools(String serverName) {
        if (!config.getServers().containsKey(serverName)) {
            return mockFallback != null
                    ? mockFallback.listTools(serverName)
                    : List.of();
        }
        McpSyncClient client = clientFor(serverName);
        McpSchema.ListToolsResult sdkResult = client.listTools();
        List<McpToolDef> out = new ArrayList<>(sdkResult.tools().size());
        for (McpSchema.Tool t : sdkResult.tools()) {
            // SDK 2.0.0 的 Tool.inputSchema() 直接是 Map<String, Object>(JSON Schema raw map)
            Map<String, Object> schema = t.inputSchema() != null
                    ? new LinkedHashMap<>(t.inputSchema())
                    : new LinkedHashMap<>();
            out.add(new McpToolDef(t.name(),
                    t.description() != null ? t.description() : "",
                    schema));
        }
        return out;
    }

    @Override
    public String callTool(String serverName, String toolName, Map<String, Object> args) {
        if (!config.getServers().containsKey(serverName)) {
            return mockFallback != null
                    ? mockFallback.callTool(serverName, toolName, args)
                    : "MCP error: server '" + serverName + "' not configured";
        }
        try {
            McpSyncClient client = clientFor(serverName);
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder()
                            .name(toolName)
                            .arguments(args != null ? args : Map.of())
                            .build()
            );
            String text = extractText(result.content());
            return Boolean.TRUE.equals(result.isError())
                    ? "MCP error: " + text
                    : text;
        } catch (Exception e) {
            log.warn("[MCP-sdk] {}.{} call failed: {}", serverName, toolName, e.toString());
            return "MCP error: " + e.getMessage();
        }
    }

    @Override
    public boolean serverExists(String serverName) {
        if (config.getServers().containsKey(serverName)) return true;
        return mockFallback != null && mockFallback.serverExists(serverName);
    }

    @Override
    public List<String> availableServers() {
        List<String> out = new ArrayList<>(config.getServers().keySet());
        if (mockFallback != null) {
            for (String name : mockFallback.availableServers()) {
                if (!out.contains(name)) out.add(name);
            }
        }
        return List.copyOf(out);
    }

    // ─────────────────────────────────────────────────────────────
    //  生命周期
    // ─────────────────────────────────────────────────────────────

    /** 容器关停时优雅关闭所有 client(子进程会收到 SIGTERM)。 */
    @PreDestroy
    public void shutdown() {
        if (clients.isEmpty()) return;
        log.info("[MCP-sdk] shutting down {} client(s)", clients.size());
        clients.forEach((name, client) -> {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.warn("[MCP-sdk] close {} failed: {}", name, e.toString());
            }
        });
        clients.clear();
    }

    // ─────────────────────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────────────────────

    /** 获取(或 lazy init)指定 server 的 SDK client。 */
    private McpSyncClient clientFor(String serverName) {
        return clients.computeIfAbsent(serverName, name -> {
            McpProperties.Server server = config.getServers().get(name);
            if (server == null) {
                throw new IllegalArgumentException(
                        "No MCP server configured for name: " + name);
            }
            if (server.getCommand() == null || server.getCommand().isBlank()) {
                throw new IllegalStateException(
                        "MCP server '" + name + "' has no 'command' configured");
            }

            ServerParameters.Builder paramsBuilder = ServerParameters.builder(server.getCommand());
            if (server.getArgs() != null && !server.getArgs().isEmpty()) {
                paramsBuilder.args(server.getArgs().toArray(new String[0]));
            }
            if (server.getEnv() != null && !server.getEnv().isEmpty()) {
                paramsBuilder.env(server.getEnv());
            }
            ServerParameters params = paramsBuilder.build();

            log.info("[MCP-sdk] starting server '{}' with command: {} {}",
                    name, server.getCommand(),
                    server.getArgs() != null ? String.join(" ", server.getArgs()) : "");

            StdioClientTransport sdkTransport = new StdioClientTransport(
                    params, McpJsonDefaults.getMapper());

            McpSyncClient client = McpClient.sync(sdkTransport).build();
            client.initialize();
            log.info("[MCP-sdk] server '{}' initialized", name);
            return client;
        });
    }

    /** 从 CallToolResult.content 抽取第一段 TextContent。 */
    private static String extractText(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) return "(no content)";
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent tc) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tc.text());
            }
            // 其他类型(ImageContent / ResourceContent / etc)目前忽略。
            // 后期优化清单加一项:image base64 编码 / resource link 文本化。
        }
        return sb.length() > 0 ? sb.toString() : "(no text content)";
    }
}
