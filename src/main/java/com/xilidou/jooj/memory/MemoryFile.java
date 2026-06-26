package com.xilidou.jooj.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Memory 文件的内存模型。对应一个 {@code .memory/<slug>.md} 文件。
 *
 * <p>对应 Python s09 的 {@code list_memory_files()} 返回的 dict 结构,
 * Java 用 POJO 更类型安全。
 *
 * <p>YAML frontmatter 三个字段:
 * <ul>
 *   <li>{@code name}:slug 来源(去空格小写化)。同名 = 同一个文件,新写覆盖旧</li>
 *   <li>{@code description}:一行概述,索引展示 + Selector LLM side-query 用</li>
 *   <li>{@code type}:四选一(user/feedback/project/reference),见 {@link Type}</li>
 * </ul>
 *
 * <p>{@code body} 是 frontmatter 之后的 Markdown 正文,LLM 注入时读它。
 *
 * <p>{@code filename} 是落盘后的文件名(slug + .md),由 {@link MemoryStore#write}
 * 计算并回填,创建时可空。
 *
 * <p>JSON 序列化兼容:Extractor 让 LLM 返回的就是这个结构,
 * 用 {@code @AllArgsConstructor} 让 Jackson 反序列化更顺。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryFile {

    /**
     * Memory 类型。对应 s09 教学版 4 种,各自回答不同的问题:
     * <ul>
     *   <li>{@link #USER} —— 谁是用户("Use tabs not spaces")</li>
     *   <li>{@link #FEEDBACK} —— 怎么干活("Don't mock the database")</li>
     *   <li>{@link #PROJECT} —— 正在发生什么("Auth rewrite is compliance-driven")</li>
     *   <li>{@link #REFERENCE} —— 东西在哪("Pipeline bugs are in Linear INGEST")</li>
     * </ul>
     *
     * <p>frontmatter 序列化为小写字符串,例如 {@code type: user}。
     */
    public enum Type {
        USER, FEEDBACK, PROJECT, REFERENCE;

        /** frontmatter 序列化用。*/
        public String slug() {
            return name().toLowerCase();
        }

        /**
         * 从 frontmatter 字符串解析。容错:大小写不敏感,未知值返回 USER 兜底
         * (而不是抛异常——单条 memory 不该让整个加载流程崩)。
         */
        public static Type parse(String s) {
            if (s == null || s.isBlank()) return USER;
            try {
                return Type.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return USER;
            }
        }
    }

    private String name;          // 必填:slug 来源
    private Type type;            // 必填
    private String description;   // 必填:一行概述
    private String body;          // 必填:Markdown 正文
    private String filename;      // 由 store 写入时回填,如 "user-preference-tabs.md"

    /**
     * 把 name 转成 slug:小写 + 空格变 {@code -} + 非法字符替成 {@code _}。
     *
     * <p>对应 Python {@code slug = name.lower().replace(" ", "-")},Java
     * 加了路径穿越防御:任何非 {@code [a-z0-9_-]} 字符替换为下划线,防止恶意
     * name 如 {@code "../../etc/passwd"} 写出沙箱。
     */
    public static String slugFromName(String name) {
        if (name == null) return "memory";
        String slug = name.trim().toLowerCase().replace(' ', '-');
        // 安全清洗:只保留 a-z 0-9 _ -
        slug = slug.replaceAll("[^a-z0-9_-]", "_");
        if (slug.isEmpty()) slug = "memory";
        return slug;
    }

    /** 便利构造:带默认 filename(由 name 推导)。*/
    public static MemoryFile of(String name, Type type, String description, String body) {
        MemoryFile m = new MemoryFile(name, type, description, body, slugFromName(name) + ".md");
        return m;
    }
}
