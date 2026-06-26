package com.xilidou.jooj.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP transport 抽象 —— 把 "tools/list" + "tools/call" 这两个 JSON-RPC 方法
 * 抽出接口,让 jooj 教学版可以用 mock 实现验证流程,生产化再加
 * stdio / http / ws / sse 等真实 transport。
 *
 * <p>对应上游 s19 的"6 种 transport 之一" —— 教学版只用 mock(进程内调用),
 * real CC 默认走 stdio(子进程 stdin/stdout JSON-RPC)。
 *
 * <h3>不为啥要抽接口</h3>
 *
 * <ul>
 *   <li>测试可以注入 mock 不依赖外部进程</li>
 *   <li>生产化只需要新写一个 transport 实现,业务逻辑(McpClient / McpRegistry)不动</li>
 *   <li>跟 jooj {@link com.xilidou.jooj.team.GitClient} 同思路 —— 外部依赖通过接口隔离</li>
 * </ul>
 */
public interface McpTransport {

    /**
     * 调 server 的 {@code tools/list},返回该 server 暴露的工具定义列表。
     *
     * <p>真实 server 这是 JSON-RPC 调用,等回 server 响应。
     * mock 实现直接返回 hard-coded 列表。
     */
    List<McpToolDef> listTools(String serverName);

    /**
     * 调 server 的 {@code tools/call},执行某个工具并返回结果。
     *
     * @param serverName server 标识(connect_mcp 时用的 name)
     * @param toolName   server 内部的原始工具名(不带 mcp__ 前缀)
     * @param args       工具调用参数
     * @return 工具输出文本
     */
    String callTool(String serverName, String toolName, Map<String, Object> args);

    /**
     * 检查 server 是否存在(可连接)。{@link McpRegistry#connect} 用来给 LLM 友好错误。
     */
    boolean serverExists(String serverName);

    /** 列出所有可连接的 server name —— 给 LLM 看 "Available: docs, deploy"。 */
    List<String> availableServers();
}
