package com.xilidou.jooj.slashcmd;

/**
 * SlashCommand —— 纯客户端命令(不进 LLM,不进 message history)。
 *
 * <p>跟 {@link com.xilidou.jooj.tool.Tool} / {@link com.xilidou.jooj.skill.Skill} 的区别:
 *
 * <table>
 *   <tr><th>类型</th><th>触发方</th><th>消耗 token</th><th>例子</th></tr>
 *   <tr><td>Tool</td><td>LLM</td><td>是(JSON schema 注入 SYSTEM)</td><td>bash / read_file</td></tr>
 *   <tr><td>Skill</td><td>LLM(看 catalog 后调 load_skill)</td><td>catalog ~100 token / skill</td><td>code-review</td></tr>
 *   <tr><td>SlashCommand</td><td>用户(输入 / 开头)</td><td>0(不进 LLM)</td><td>/clear /help /sessions</td></tr>
 * </table>
 *
 * <p>实现策略:用户在 CLI / Web 输入 `/clear` 时,入口层(JoojCliRunner / ChatController)
 * 先看是否 `/` 开头 → 走 {@link SlashCommandRegistry#dispatch} → 不走 LLM。
 *
 * <p>未识别命令(如 `/foo`)由 registry 兜底返 "Unknown command: /foo. Available: ...",
 * 不退化成 prompt 喂 LLM —— 避免用户分不清是不是真命令。
 */
public interface SlashCommand {

    /** 命令名(不含 /),全小写。例如 "clear"、"help"、"sessions"。 */
    String name();

    /** 一行说明,/help 显示用。 */
    String description();

    /**
     * 执行命令。
     *
     * @param args      命令后面的参数(已去掉命令名,可能为空字符串)
     * @param sessionId 当前 session id(命令可能要操作此 session 的状态)
     * @return 执行结果文本(给 CLI / Web 显示)
     */
    String execute(String args, String sessionId);
}
