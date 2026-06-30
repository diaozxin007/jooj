package com.xilidou.jooj.skill;

import java.util.regex.Pattern;

/**
 * SkillFrontmatterValidator — 按 <a href="https://agentskills.io/specification">agentskills.io spec</a>
 * 校验 SKILL.md frontmatter。
 *
 * <h3>谁会用</h3>
 *
 * <ul>
 *   <li>{@link SkillRegistry} 加载时校验(s21 Demo 17),不合规拒绝加载该 skill</li>
 *   <li>未来 skill 自创建工具调用前校验(确保 LLM 写出来的格式合法)</li>
 *   <li>测试守门:外部贡献的 skill 加进 jooj 前先验</li>
 * </ul>
 *
 * <h3>设计</h3>
 *
 * <p>纯函数,无状态。每个 validate 方法返回:
 * <ul>
 *   <li>null = 合法</li>
 *   <li>非 null = 错误描述字符串(供 log / UI 显示)</li>
 * </ul>
 */
public final class SkillFrontmatterValidator {

    /** spec § name field:1-64 字符,小写字母 / 数字 / 单连字符 */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final int NAME_MAX = 64;
    private static final int DESCRIPTION_MAX = 1024;
    private static final int COMPATIBILITY_MAX = 500;

    private SkillFrontmatterValidator() {}

    /**
     * 校验 name 字段。
     *
     * @param name      frontmatter 里 name 的值
     * @param dirName   skill 父目录名(spec 要求两者必须相同)
     * @return null 合法,否则错误描述
     */
    public static String validateName(String name, String dirName) {
        if (name == null || name.isBlank()) {
            return "name is required (1-" + NAME_MAX + " chars)";
        }
        if (name.length() > NAME_MAX) {
            return "name too long: " + name.length() + " > " + NAME_MAX;
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            return "name format invalid: '" + name + "' — must be lowercase letters/digits/single hyphens, "
                    + "no leading/trailing/consecutive hyphens";
        }
        if (dirName != null && !name.equals(dirName)) {
            return "name '" + name + "' must equal parent dir name '" + dirName + "'";
        }
        return null;
    }

    /** 校验 description 字段。spec:1-1024 字符,必填。 */
    public static String validateDescription(String description) {
        if (description == null || description.isBlank()) {
            return "description is required (1-" + DESCRIPTION_MAX + " chars)";
        }
        if (description.length() > DESCRIPTION_MAX) {
            return "description too long: " + description.length() + " > " + DESCRIPTION_MAX;
        }
        return null;
    }

    /** 校验可选 compatibility 字段。spec:≤500 字符。null 视为未声明,合法。 */
    public static String validateCompatibility(String compatibility) {
        if (compatibility == null) return null;
        if (compatibility.isBlank()) return null;
        if (compatibility.length() > COMPATIBILITY_MAX) {
            return "compatibility too long: " + compatibility.length() + " > " + COMPATIBILITY_MAX;
        }
        return null;
    }
}
