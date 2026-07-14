package com.xilidou.jooj.skill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill 子系统(s07)的 yml → Java 桥接。
 *
 * <p>字段极简 —— 只有 dir 一项,豁免三分法(参考 Mcp / Permission)。
 * 直接由 {@link SkillRegistry} 消费。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Skills} 拆出,前缀 {@code jooj.skills} 保持不变。
 * 同时 SkillRegistry 从 {@code @Value("${jooj.skills.dir:skills}")} 迁移到直接注入本 Properties,
 * 统一配置读取路径。
 */
@Data
@ConfigurationProperties("jooj.skills")
public class SkillProperties {

    /**
     * skills 目录路径,相对 cwd 或绝对路径。默认 {@code skills}。
     *
     * <p>目录结构约定:{@code &lt;dir&gt;/&lt;skill_name&gt;/SKILL.md} 是每个 skill 的入口。
     */
    private String dir = "skills";
}
