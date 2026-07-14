package com.xilidou.jooj;

/**
 * jooj 配置系统已完成子系统化拆分(2026-07-14 配置架构重构完成)。
 *
 * <p>原本 484 行的上帝配置类,已按子系统拆分成 14 个独立 {@code *Properties} 类:
 * <table>
 *   <tr><th>子系统</th><th>Properties 类</th><th>yml 前缀</th></tr>
 *   <tr><td>Anthropic</td><td>{@link com.xilidou.jooj.http.AnthropicProperties}</td><td>{@code jooj.anthropic}</td></tr>
 *   <tr><td>DeepSeek</td><td>{@link com.xilidou.jooj.http.DeepSeekProperties}</td><td>{@code jooj.deepseek}</td></tr>
 *   <tr><td>Compact</td><td>{@link com.xilidou.jooj.compact.CompactProperties}</td><td>{@code jooj.compact}</td></tr>
 *   <tr><td>Memory</td><td>{@link com.xilidou.jooj.memory.MemoryProperties}</td><td>{@code jooj.memory}</td></tr>
 *   <tr><td>Permission</td><td>{@link com.xilidou.jooj.permission.PermissionProperties}</td><td>{@code jooj.permission}</td></tr>
 *   <tr><td>Skills</td><td>{@link com.xilidou.jooj.skill.SkillProperties}</td><td>{@code jooj.skills}</td></tr>
 *   <tr><td>Prompt</td><td>{@link com.xilidou.jooj.prompt.PromptProperties}</td><td>{@code jooj.prompt}</td></tr>
 *   <tr><td>Recovery</td><td>{@link com.xilidou.jooj.agent.RecoveryProperties}</td><td>{@code jooj.recovery}</td></tr>
 *   <tr><td>Tasks</td><td>{@link com.xilidou.jooj.tasks.TasksProperties}</td><td>{@code jooj.tasks}</td></tr>
 *   <tr><td>Cron</td><td>{@link com.xilidou.jooj.cron.CronProperties}</td><td>{@code jooj.cron}</td></tr>
 *   <tr><td>Team</td><td>{@link com.xilidou.jooj.team.TeamProperties}</td><td>{@code jooj.team}</td></tr>
 *   <tr><td>Concurrency</td><td>{@link com.xilidou.jooj.config.ConcurrencyProperties}</td><td>{@code jooj.concurrency}</td></tr>
 *   <tr><td>Mcp</td><td>{@link com.xilidou.jooj.mcp.McpProperties}</td><td>{@code jooj.mcp}</td></tr>
 *   <tr><td>Search</td><td>{@link com.xilidou.jooj.search.SearchProperties}</td><td>{@code jooj.search}</td></tr>
 * </table>
 *
 * <p><b>本类的当前角色</b>:仅作为迁移索引 —— 阶段 3-② 决定是否彻底删除。
 * {@code @ConfigurationPropertiesScan}(在 {@link JoojApplication} 上)会自动
 * 发现所有子 Properties 类,不再需要顶层聚合。
 *
 * @see JoojApplication#main(String[])
 */
public class JoojProperties {
    // 空壳 —— 所有配置已迁移到各子系统的独立 *Properties 类。
    // 阶段 3-② 决定是否彻底删除本文件。
}
