package com.xilidou.marvis.skill;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Skill - 专家知识包（不是工具）。
 *
 * <p>对应 Python s07 的 SKILL.md 文件结构：
 * <pre>
 *   ---
 *   name: code-review
 *   description: Perform thorough code reviews...
 *   ---
 *
 *   # Code Review Skill
 *
 *   You now have expertise in conducting comprehensive code reviews.
 *   ...
 * </pre>
 *
 * <h3>Skill vs Tool</h3>
 *
 * <p>这是 marvis 里**两个不同概念**的清晰边界：
 *
 * <table>
 *   <tr><th>维度</th><th>{@link com.xilidou.marvis.tool.Tool Tool}</th><th>{@link Skill}</th></tr>
 *   <tr><td>本质</td><td>函数 + JSON schema</td><td>文本指令 + 例子</td></tr>
 *   <tr><td>作用方式</td><td>LLM 调用产生 side effect</td><td>注入 LLM context 改变行为</td></tr>
 *   <tr><td>例子</td><td>bash, read_file</td><td>code-review, agent-builder</td></tr>
 *   <tr><td>加载</td><td>启动时全部注册</td><td>SYSTEM 只放 catalog，body 按需 load</td></tr>
 * </table>
 *
 * <h3>两层加载（s07 核心）</h3>
 *
 * <p>Layer 1（廉价，永远在 SYSTEM）：name + description（每个 skill ~100 token）
 * <p>Layer 2（昂贵，按需）：完整 body（每个 ~2000+ token）
 *
 * <p>LLM 看到 catalog 后，决定调 {@code load_skill(name)} 工具拉取完整指令。
 */
@Data
@AllArgsConstructor
public class Skill {

    /** Skill 名字，唯一标识。来自 YAML frontmatter 的 name 字段或目录名 */
    private final String name;

    /** 简短描述，注入 SYSTEM prompt catalog 用。来自 YAML frontmatter 的 description */
    private final String description;

    /** 完整 SKILL.md 内容（含 frontmatter 和 body）。load_skill 时返回 */
    private final String body;
}
