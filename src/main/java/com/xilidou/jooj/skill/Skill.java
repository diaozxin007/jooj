package com.xilidou.jooj.skill;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * Skill — 专家知识包(不是工具)。对齐 <a href="https://agentskills.io/specification">agentskills.io 标准</a>。
 *
 * <p>对应 Python s07 的 SKILL.md 文件结构。s21 Demo 17 把字段集对齐 agentskills.io spec,
 * 之前只读 name + description,现在读全 frontmatter 6 个字段。
 *
 * <h3>SKILL.md 例子</h3>
 *
 * <pre>
 *   ---
 *   name: code-review
 *   description: Perform thorough code reviews...
 *   license: MIT
 *   compatibility: Designed for Claude Code (or similar products)
 *   metadata:
 *     author: example-org
 *     version: "1.0"
 *   allowed-tools: Bash(git:*) Read
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
 * <p>这是 jooj 里**两个不同概念**的清晰边界:
 *
 * <table>
 *   <tr><th>维度</th><th>{@link com.xilidou.jooj.tool.Tool Tool}</th><th>{@link Skill}</th></tr>
 *   <tr><td>本质</td><td>函数 + JSON schema</td><td>文本指令 + 例子</td></tr>
 *   <tr><td>作用方式</td><td>LLM 调用产生 side effect</td><td>注入 LLM context 改变行为</td></tr>
 *   <tr><td>例子</td><td>bash, read_file</td><td>code-review, agent-builder</td></tr>
 *   <tr><td>加载</td><td>启动时全部注册</td><td>SYSTEM 只放 catalog,body 按需 load</td></tr>
 * </table>
 *
 * <h3>两层加载(s07 核心) + spec 第三层</h3>
 *
 * <p>Layer 1(廉价,永远在 SYSTEM):name + description(每个 skill ~100 token)
 * <p>Layer 2(昂贵,按需):完整 body(每个 ~2000+ token)
 * <p>Layer 3(spec 推荐,jooj 暂未实现):scripts/ + references/ + assets/ 子目录,LLM 通过
 * read_file / bash 按需读 —— 见笔记 jooj_改造日志_s21 Demo 17 的 Tier C
 */
@Data
@AllArgsConstructor
public class Skill {

    /** Skill 名字,唯一标识。来自 YAML frontmatter 的 name 字段。
     *  spec:1-64 字符,小写字母+数字+连字符,不能首尾连字符或连续连字符,必须等于父目录名。 */
    private final String name;

    /** 简短描述,注入 SYSTEM prompt catalog 用。spec:1-1024 字符,描述"做什么 + 何时用"。 */
    private final String description;

    /** 完整 SKILL.md 内容(含 frontmatter 和 body)。load_skill 时返回。 */
    private final String body;

    /** 可选,license 名称或 bundled license 文件引用。null 表示未声明。 */
    private final String license;

    /** 可选,环境兼容性(目标 product / 系统包 / 网络访问等),≤500 字符。null 表示未声明。 */
    private final String compatibility;

    /** 可选,任意 string→string 元数据 map。spec 建议 key 加 namespace 前缀避免冲突。
     *  null 或空 map 表示未声明。 */
    private final Map<String, String> metadata;

    /** 可选,**实验性** —— 空格分隔的预批准工具列表,如 {@code "Bash(git:*) Read"}。
     *  jooj 暂只读取存档,不据此做 sandbox。null 表示未声明。 */
    private final String allowedTools;
}
