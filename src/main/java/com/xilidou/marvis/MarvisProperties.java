package com.xilidou.marvis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * marvis 顶层配置 —— 切片 C(Spring Boot 化)的入口。
 *
 * <p>把所有 {@code ANTHROPIC_*}/{@code MODEL_ID} 等环境变量 +
 * Compact/Memory/Permission 阈值统一收口在这里,通过
 * {@link ConfigurationProperties} 绑定到 {@code marvis.*} 配置树。
 *
 * <p><b>为什么用 Lombok @Data + 嵌套静态 class</b>:
 * <ul>
 *   <li>Spring Boot 4 的 {@code @ConfigurationProperties} 默认走 setter 绑定;
 *       {@code @Data} 一次性生成 getter/setter,与现有 Lombok 风格一致</li>
 *   <li>嵌套静态 class 让 yaml 可以分组 ({@code marvis.anthropic.*}),
 *       同时只需一个顶层 Bean,扫描成本最小</li>
 *   <li>不用 record:record 是 immutable,与 setter 绑定模型不兼容,
 *       Spring 4 的 record-binding 还要求构造器参数顺序与 yaml 完全对齐,易脆</li>
 * </ul>
 *
 * <p><b>启用方式</b>:
 * 在 {@link MarvisApplication} 上加 {@code @ConfigurationPropertiesScan},
 * Spring 会自动把这个类注册为 Bean,其它 Bean 通过构造器注入即可使用。
 *
 * @see com.xilidou.marvis.http.HttpClientConfig
 * @see com.xilidou.marvis.compact.CompactConfiguration
 * @see com.xilidou.marvis.memory.MemoryConfiguration
 * @see com.xilidou.marvis.permission.PermissionConfiguration
 */
@Data
@ConfigurationProperties("marvis")
public class MarvisProperties {

    /** Anthropic / 兼容代理的 HTTP 配置。 */
    private Anthropic anthropic = new Anthropic();

    /** Compact 流水线(s08)阈值。 */
    private Compact compact = new Compact();

    /** Memory(s09)阈值。 */
    private Memory memory = new Memory();

    /** Permission(s03)模式。 */
    private Permission permission = new Permission();

    /** Skill(s07)路径配置。 */
    private Skills skills = new Skills();

    /** SYSTEM prompt(s10)模板配置 —— 运行期由 SystemPromptAssembler 按 context 选段。 */
    private Prompt prompt = new Prompt();

    @Data
    public static class Anthropic {
        /** API 根 URL,默认 https://api.anthropic.com。 */
        private String baseUrl = "https://api.anthropic.com";

        /** Anthropic 官方 API Key(x-api-key header)。与 authToken 二选一。 */
        private String apiKey = "";

        /** 公司代理 / 兼容供应商的 Bearer Token(Authorization header)。与 apiKey 二选一。 */
        private String authToken = "";

        /** 模型 ID,如 {@code claude-sonnet-4-6}。 */
        private String model = "";
    }

    @Data
    public static class Compact {
        private int maxMessages = 50;
        private int snipHeadKeep = 3;
        private int keepRecent = 3;
        private int minPlaceholderLen = 120;
        private int maxToolResultBytes = 10000;
        private int summaryHeadKeep = 3;
        private int summaryTailKeep = 10;
        private int summaryMaxChars = 500;
    }

    @Data
    public static class Memory {
        /** memory 文件目录(相对 cwd 或绝对路径)。 */
        private String memoryDir = ".memory";

        /** 索引文件名,放在 {@link #memoryDir} 下。 */
        private String indexFilename = "MEMORY.md";

        /** 单条 memory body 最大字符数(超出截断)。 */
        private int maxBodyBytes = 4096;

        /** memory 文件数 ≥ 此阈值时触发 consolidate。 */
        private int consolidateThreshold = 10;
    }

    @Data
    public static class Permission {
        /**
         * 权限模式:
         * <ul>
         *   <li>{@code cli} — 默认,Gate 1 + Gate 2 + 控制台 Gate 3</li>
         *   <li>{@code always-allow} — 测试或 batch 场景,所有 ASK 自动通过</li>
         *   <li>{@code always-deny} — 测试场景,所有 ASK 自动拒绝</li>
         * </ul>
         */
        private String mode = "cli";
    }

    @Data
    public static class Skills {
        /** skills/ 目录(相对 cwd 或绝对路径)。 */
        private String dir = "skills";
    }

    /**
     * SYSTEM prompt 片段模板(s10)。
     *
     * <p>对应 Python {@code PROMPT_SECTIONS} 字典。每个字段是一段命名 prompt,
     * 由 {@link com.xilidou.marvis.prompt.SystemPromptAssembler} 在运行期
     * 按 context 选取拼接。
     *
     * <p><b>哪些字段允许用户 override</b>:
     * <ul>
     *   <li>{@code identity} / {@code tools} — 用户在 yml 里可以覆盖,
     *       让 agent persona 可定制(例如改成"你是一个评测专家")</li>
     *   <li>{@code workspace} — 不暴露,自动从 {@code System.getProperty("user.dir")} 读,
     *       避免用户配错路径</li>
     *   <li>{@code memoryHeader} — 仅控制 memory section 的标题前缀,
     *       memory 正文由 {@link com.xilidou.marvis.memory.MemoryService} 提供</li>
     * </ul>
     */
    @Data
    public static class Prompt {
        /** identity section:agent 自我定位,通常一两句话。 */
        private String identity =
                "You are a coding agent. " +
                "Before starting any multi-step task, use todo_write to plan your steps. " +
                "Update task status as you go. Act, don't explain.";

        /** tools section:可用工具的概述(具体 ToolDef 仍通过 Anthropic API 协议传)。 */
        private String tools =
                "Available tools: bash, read_file, write_file, edit_file, glob, " +
                "todo_write, load_skill, task.";

        /**
         * memory section 的标题前缀。memory 正文由 MemoryService.catalog() 提供,
         * 此前缀放在正文之前形成完整的 memory section。
         */
        private String memoryHeader = "Memory index (long-term knowledge from past sessions):";
    }
}
