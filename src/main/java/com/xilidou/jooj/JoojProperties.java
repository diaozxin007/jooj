package com.xilidou.jooj;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * jooj 顶层配置 —— 切片 C(Spring Boot 化)的入口。
 *
 * <p>把所有 {@code ANTHROPIC_*}/{@code MODEL_ID} 等环境变量 +
 * Compact/Memory/Permission 阈值统一收口在这里,通过
 * {@link ConfigurationProperties} 绑定到 {@code jooj.*} 配置树。
 *
 * <p><b>为什么用 Lombok @Data + 嵌套静态 class</b>:
 * <ul>
 *   <li>Spring Boot 4 的 {@code @ConfigurationProperties} 默认走 setter 绑定;
 *       {@code @Data} 一次性生成 getter/setter,与现有 Lombok 风格一致</li>
 *   <li>嵌套静态 class 让 yaml 可以分组 ({@code jooj.anthropic.*}),
 *       同时只需一个顶层 Bean,扫描成本最小</li>
 *   <li>不用 record:record 是 immutable,与 setter 绑定模型不兼容,
 *       Spring 4 的 record-binding 还要求构造器参数顺序与 yaml 完全对齐,易脆</li>
 * </ul>
 *
 * <p><b>启用方式</b>:
 * 在 {@link JoojApplication} 上加 {@code @ConfigurationPropertiesScan},
 * Spring 会自动把这个类注册为 Bean,其它 Bean 通过构造器注入即可使用。
 *
 * @see com.xilidou.jooj.http.HttpClientConfiguration
 * @see com.xilidou.jooj.compact.CompactConfiguration
 * @see com.xilidou.jooj.memory.MemoryConfiguration
 * @see com.xilidou.jooj.permission.PermissionConfiguration
 */
@Data
@ConfigurationProperties("jooj")
public class JoojProperties {

    /** Anthropic / 兼容代理的 HTTP 配置。 */
    private Anthropic anthropic = new Anthropic();

    /** DeepSeek (Anthropic 兼容模式) 配置。可选 —— 不配则不注册。 */
    private DeepSeek deepseek = new DeepSeek();

    /** Compact 流水线(s08)配置已拆到 {@link com.xilidou.jooj.compact.CompactProperties}(2026-07-14)。 */

    /** Memory 子系统(s09)配置已拆到 {@link com.xilidou.jooj.memory.MemoryProperties}(2026-07-14)。 */

    /** Permission(s03)配置已拆到 {@link com.xilidou.jooj.permission.PermissionProperties}(2026-07-14)。 */

    /** Skill(s07)配置已拆到 {@link com.xilidou.jooj.skill.SkillProperties}(2026-07-14)。 */

    /** Prompt(s10)配置已拆到 {@link com.xilidou.jooj.prompt.PromptProperties}(2026-07-14)。 */

    /** Recovery(s11)配置已拆到 {@link com.xilidou.jooj.agent.RecoveryProperties}(2026-07-14)。 */

    /** Tasks(s12)配置已拆到 {@link com.xilidou.jooj.tasks.TasksProperties}(2026-07-14)。 */

    /** Cron(s14)配置已拆到 {@link com.xilidou.jooj.cron.CronProperties}(2026-07-14)。 */

    /** Team(s15+)配置已拆到 {@link com.xilidou.jooj.team.TeamProperties}(2026-07-14)。 */

    /** 并发 / 线程池(线程重构)配置 —— 替代裸 {@code new Thread()}。 */
    private Concurrency concurrency = new Concurrency();

    /** Search(s21 Demo 25)配置已拆到 {@link com.xilidou.jooj.search.SearchProperties}(2026-07-14)。 */

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

    /**
     * DeepSeek 配置(Anthropic 兼容模式)。
     * 使用 {@code https://api.deepseek.com/anthropic} 端点,协议与 Anthropic 完全相同。
     */
    @Data
    public static class DeepSeek {
        /** API 根 URL,默认 https://api.deepseek.com/anthropic。 */
        private String baseUrl = "https://api.deepseek.com/anthropic";

        /** DeepSeek API Key(通过 x-api-key header 发送)。为空则不注册该 provider。 */
        private String apiKey = "";

        /** 模型 ID,如 {@code deepseek-chat}。 */
        private String model = "";
    }

    /**
     * Recovery(s11)配置已拆到 {@link com.xilidou.jooj.agent.RecoveryProperties}(2026-07-14)。
     */

    /**
    /**
     * Team(s15+)配置已拆到 {@link com.xilidou.jooj.team.TeamProperties}(2026-07-14)。
     */

    /**
     * 并发 / 线程池配置 —— 配合 {@link com.xilidou.jooj.config.JoojExecutors}。
     *
     * <p>jooj 用三类池:
     * <ul>
     *   <li>{@code schedulerPoolSize} —— 长期循环任务({@code @Scheduled})的池容量,
     *       当前 jooj 有 cron scheduler + cron processor 两个长期任务,默认 4 槽</li>
     *   <li>{@code bgPoolSize} —— BG 慢工具调用池(s13),默认 8。
     *       池策略 {@code CallerRunsPolicy}:满则降级同步,LLM 仍能拿到结果只是慢一点</li>
     *   <li>{@code teammatePoolSize} —— Teammate spawn 池(s15+),默认 16。
     *       池策略 {@code AbortPolicy}:满则抛 {@code RejectedExecutionException},
     *       Teammate 返"Error: pool full"给 LLM 让它降并发(不能 inline 跑,
     *       会卡死 agent loop 几分钟)</li>
     * </ul>
     */
    @Data
    public static class Concurrency {
        /** 长期循环任务({@code @Scheduled})的调度池容量。默认 4。 */
        private int schedulerPoolSize = 4;
        /** BG 慢工具池上限(s13)。CallerRunsPolicy 满则同步降级。默认 8。 */
        private int bgPoolSize = 8;
        /** Teammate spawn 池上限(s15+)。AbortPolicy 满则返 Error 给 LLM。默认 16。 */
        private int teammatePoolSize = 16;
    }

    /**
     * MCP plugin(s19)配置已拆到 {@link com.xilidou.jooj.mcp.McpProperties}(2026-07-14)。
     * 前缀依然是 {@code jooj.mcp}。
     */

    /**
     * Search(s21 Demo 25)配置已拆到 {@link com.xilidou.jooj.search.SearchProperties}(2026-07-14)。
     */
}
