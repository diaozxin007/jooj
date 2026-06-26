package com.xilidou.jooj.mcp;

import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一个已连接的 MCP server —— 持有 transport 引用 + 它暴露的工具列表。
 *
 * <p>对应上游 s19 的 {@code MCPClient} class。
 *
 * <p>不存在 "constructor 时打开连接 / close 时关闭"语义 —— transport 是共享 Bean,
 * client 只是一个 server 状态快照(name + 该 server 当前工具)。
 *
 * <p>{@link McpRegistry#connect} 调 {@code transport.listTools(name)} 之后,把
 * 结果放进 {@code tools} 字段冻结。后续 LLM 调 {@code mcp__name__tool} 时,
 * 通过 {@link #callTool} 转发到 transport。
 */
@Getter
public class McpClient {

    private final String name;
    private final McpTransport transport;
    private final List<McpToolDef> tools;

    public McpClient(String name, McpTransport transport, List<McpToolDef> tools) {
        this.name = name;
        this.transport = transport;
        this.tools = Collections.unmodifiableList(tools);
    }

    /**
     * 调本 client 关联 server 的某个工具。
     *
     * @param toolName server 内部原始工具名(不带 {@code mcp__} 前缀)
     * @param args     工具参数
     * @return 工具输出文本
     */
    public String callTool(String toolName, Map<String, Object> args) {
        return transport.callTool(name, toolName, args);
    }
}
