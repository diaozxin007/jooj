package com.xilidou.jooj;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @see com.xilidou.jooj.http.HttpClientConfig
 * @see com.xilidou.jooj.compact.CompactConfiguration
 * @see com.xilidou.jooj.memory.MemoryConfiguration
 * @see com.xilidou.jooj.permission.PermissionConfiguration
 */
@Data
@ConfigurationProperties("jooj")
public class JoojProperties {

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

    /** 错误恢复(s11)阈值。 */
    private Recovery recovery = new Recovery();

    /** Task System(s12)路径配置。 */
    private Tasks tasks = new Tasks();

    /** Cron Scheduler(s14)tick + 持久化路径配置。 */
    private Cron cron = new Cron();

    /** Team / MessageBus(s15)邮箱目录配置。 */
    private Team team = new Team();

    /** 并发 / 线程池(线程重构)配置 —— 替代裸 {@code new Thread()}。 */
    private Concurrency concurrency = new Concurrency();

    /** s19 MCP plugin —— 真实 SDK 配置(stdio 子进程)。 */
    private Mcp mcp = new Mcp();

    /**
     * s21 Demo 25: Session 全文搜索(SQLite + FTS5)配置。
     * SearchStore 在 jooj home 下建一个 search.db,跟 sessions/ 目录平级。
     */
    private Search search = new Search();

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

        /**
         * s21 Demo 27 / Hermes Tier 3 P3.2:write_approval staged 写。
         * <ul>
         *   <li>{@code false}(默认)— Reviewer 提案直接 store.write 生效(Demo 26 行为)</li>
         *   <li>{@code true} — Reviewer 提案进 pending pool,等用户 {@code /memory approve}</li>
         * </ul>
         *
         * <p>实战建议:Reviewer 跑稳之前先开 true 看提案质量,稳定后再切 false。
         */
        private boolean writeApproval = false;
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
     * 由 {@link com.xilidou.jooj.prompt.SystemPromptAssembler} 在运行期
     * 按 context 选取拼接。
     *
     * <p><b>哪些字段允许用户 override</b>:
     * <ul>
     *   <li>{@code identity} / {@code tools} — 用户在 yml 里可以覆盖,
     *       让 agent persona 可定制(例如改成"你是一个评测专家")</li>
     *   <li>{@code workspace} — 不暴露,自动从 {@code System.getProperty("user.dir")} 读,
     *       避免用户配错路径</li>
     *   <li>{@code memoryHeader} — 仅控制 memory section 的标题前缀,
     *       memory 正文由 {@link com.xilidou.jooj.memory.MemoryService} 提供</li>
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
                "todo_write, load_skill, task, " +
                "create_task, list_tasks, get_task, claim_task, complete_task, " +
                "schedule_cron, list_crons, cancel_cron, " +
                "spawn_teammate, send_message, check_inbox, " +
                "create_worktree, remove_worktree, keep_worktree, " +
                "connect_mcp (+ dynamic mcp__server__tool after connect), " +
                "session_search. " +
                "For slow ops (build/test/deploy/install), set bash.run_in_background=true " +
                "to keep working while it runs in the background.";

        /**
         * memory section 的标题前缀。memory 正文由 MemoryService.catalog() 提供,
         * 此前缀放在正文之前形成完整的 memory section。
         */
        private String memoryHeader = "Memory index (long-term knowledge from past sessions):";

        /**
         * skill section 的标题前缀。skill 正文由 SkillRegistry.catalog() 提供,
         * 此前缀放在正文之前形成完整的 skill section。
         *
         * <p>提示 LLM 怎么用:catalog 只列出 name + description,需要完整内容时
         * 调 {@code load_skill(name=...)} 工具。空 catalog 时整段 skill section 跳过。
         */
        private String skillsHeader =
                "Available skills (call load_skill(name=...) to load full content):";
    }

    /**
     * 错误恢复阈值(s11)。配合 {@link com.xilidou.jooj.agent.RecoveryCoordinator}
     * 处理 3 类常见错误:
     * <ul>
     *   <li><b>Path 1</b>(max_tokens 截断):{@code defaultMaxTokens} → {@code escalatedMaxTokens}
     *       升级一次,仍截断时通过 continuation prompt 续写,最多 {@code maxRecoveryRetries} 次</li>
     *   <li><b>Path 2</b>(prompt_too_long):reactive compact 一次,不行抛</li>
     *   <li><b>Path 3</b>(429/529 限流过载):指数退避 + 抖动重试 {@code maxRetries} 次;
     *       连续 {@code maxConsecutive529} 次 529 后切换到 {@code fallbackModel}</li>
     * </ul>
     *
     * <p>默认值是生产可用配置。测试 profile 通常调小 {@code maxRetries} / {@code baseDelayMs}
     * 让退避测试快速失败。
     */
    @Data
    public static class Recovery {

        /** 单次 LLM 调用最多重试次数(429/529 走重试路径)。 */
        private int maxRetries = 10;

        /** 退避基数(毫秒),实际延迟 = min(base × 2^attempt, max) + 抖动。 */
        private int baseDelayMs = 500;

        /** 退避封顶(毫秒),防止指数膨胀到分钟级。 */
        private int maxDelayMs = 32_000;

        /** 连续多少次 529 后切 {@code fallbackModel}。0 表示永不切。 */
        private int maxConsecutive529 = 3;

        /**
         * 备胎模型 ID,空字符串 = 不切。
         *
         * <p>典型场景:主模型在用 Sonnet,fallback 配 Haiku 或更便宜的快速模型,
         * 主模型连续过载时降级到 fallback,保持服务可用。
         */
        private String fallbackModel = "";

        /** 主请求默认 max_tokens。 */
        private int defaultMaxTokens = 8000;

        /** Path 1 升级后的 max_tokens(为多数模型的最大输出上限留余量)。 */
        private int escalatedMaxTokens = 64_000;

        /**
         * Path 1 升级后仍截断时,通过 continuation prompt 续写的最多次数。
         * 超过则放弃,返回截断的 assistant 输出 + Fatal 标记。
         */
        private int maxRecoveryRetries = 3;

        /** Path 1 续写时插入的 user prompt。 */
        private String continuationPrompt =
                "Your previous response was cut off. Continue from where you left off.";
    }

    /**
     * Task System(s12)目录配置。对应 Python 的 {@code TASKS_DIR = WORKDIR / ".tasks"}。
     *
     * <p>每个 task 一个 JSON 文件,文件名是 task id(形如 {@code task_1729000000_3812.json})。
     * 教学版不加 file lock —— 与上游严格一致;jooj 是单进程 REPL,不会并发写。
     */
    @Data
    public static class Tasks {
        /** task 文件目录(相对 cwd 或绝对路径)。默认 {@code .tasks}。 */
        private String tasksDir = ".tasks";
    }

    /**
     * Cron Scheduler(s14)配置。对应上游
     * [s14_cron_scheduler/code.py] 的 4 层架构 + durable 持久化。
     *
     * <p>tick 间隔影响响应延迟与 CPU 开销。生产场景默认值即可:
     * <ul>
     *   <li>Layer 1 scheduler tick = 1000ms — 1 秒钟检查一次哪些 job 该 fire</li>
     *   <li>Layer 3 processor tick = 200ms — 200ms 检查一次 queue 是否有 fired job</li>
     * </ul>
     *
     * <p>测试 profile 把这俩调小让 cron-fire 测试快速完成。
     */
    @Data
    public static class Cron {
        /** Layer 1 CronScheduler 轮询间隔(毫秒)。默认 1000。 */
        private int schedulerTickMs = 1000;
        /** Layer 3 CronQueueProcessor 轮询间隔(毫秒)。默认 200。 */
        private int processorTickMs = 200;
        /** durable 持久化文件路径(相对 cwd 或绝对)。默认 {@code .scheduled_tasks.json}。 */
        private String durablePath = ".scheduled_tasks.json";
    }

    /**
     * Team / MessageBus(s15)配置 —— 文件邮箱形态的 agent 间通信。
     *
     * <p>跟上游 s15 {@code MAILBOX_DIR = WORKDIR / ".mailboxes"} 一致,
     * 每个 agent 一个 {@code <name>.jsonl} 文件。
     *
     * <p>s17 加 idle 配置:Teammate 进入 IDLE 阶段后每 {@code idlePollMs} 毫秒
     * 轮询一次(看 inbox + scan TaskBoard),累计超过 {@code idleTimeoutMs} 无活就退出。
     */
    @Data
    public static class Team {
        /** mailbox 目录(相对 cwd 或绝对路径)。默认 {@code .mailboxes}。 */
        private String mailboxDir = ".mailboxes";
        /**
         * IDLE 阶段轮询间隔(毫秒)。默认 5000(对齐上游 s17 IDLE_POLL_INTERVAL=5)。
         * 测试 profile 可调小到 50ms 让测试跑得快。
         */
        private long idlePollMs = 5000;
        /**
         * IDLE 阶段总超时(毫秒)—— 累计这么久没活就退出 teammate。
         * 默认 60000(对齐上游 s17 IDLE_TIMEOUT=60)。测试可调小到 200ms。
         */
        private long idleTimeoutMs = 60_000;
        /**
         * s18 worktree 根目录(相对 jooj workdir 或绝对路径)。默认 {@code .worktrees}。
         * 跟上游 s18 {@code WORKTREES_DIR = WORKDIR / ".worktrees"} 一致。
         */
        private String worktreeDir = ".worktrees";
    }

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
     * MCP plugin(s19)真实 SDK 配置 —— 只描述 stdio 子进程类型的 server。
     *
     * <p>每个 server 一个 {@link Server} 子配置:
     * <pre>
     *   jooj:
     *     mcp:
     *       servers:
     *         filesystem:
     *           command: npx
     *           args: ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/notes"]
     *           env: { LOG_LEVEL: "info" }
     *         git:
     *           command: npx
     *           args: ["-y", "@modelcontextprotocol/server-git", "--repo", "."]
     * </pre>
     *
     * <p>路由规则(由 {@link com.xilidou.jooj.mcp.McpRegistry} 实施):
     * <ul>
     *   <li>{@code connect("filesystem")} 时,先看 yml 是否有 {@code servers.filesystem} —— 有就走真实 SDK</li>
     *   <li>没配置则退化到 mock(原 docs / deploy 还能用)</li>
     * </ul>
     */
    @Data
    public static class Mcp {
        /** 启动 stdio 子进程时,等待 server 进入 ready 的超时(毫秒)。默认 30s 给冷启动 npm 拉包余地。 */
        private long startupTimeoutMs = 30_000;
        /** 单次 listTools / callTool 调用超时(毫秒)。默认 60s。 */
        private long callTimeoutMs = 60_000;
        /** server 配置表,key = server name(LLM 在 connect_mcp 工具里用的 name)。 */
        private Map<String, Server> servers = new LinkedHashMap<>();

        /** 单个 stdio MCP server 配置。 */
        @Data
        public static class Server {
            /** 启动命令(如 {@code npx} / {@code node} / 绝对可执行路径)。 */
            private String command;
            /** 命令参数列表。 */
            private List<String> args = new ArrayList<>();
            /** 子进程环境变量(覆盖父进程同名)。 */
            private Map<String, String> env = new LinkedHashMap<>();
        }
    }

    /**
     * Session 全文搜索(s21 Demo 25):SQLite + FTS5 索引子系统配置。
     *
     * <p>JSON 仍是 history 的 source-of-truth({@code ~/.jooj/sessions/&lt;id&gt;.json}),
     * SQLite 是衍生 view —— SessionService.saveHistory 主流程同步双写,失败 warn 不挡 JSON 主流程。
     * SQLite 损坏可重建({@link com.xilidou.jooj.search.SearchService#rebuildAll} 入口扫所有 JSON 重灌 FTS5)。
     *
     * <h3>FTS5 schema</h3>
     *
     * <p>contentful 单 virtual table:索引列 {@code content},UNINDEXED 元数据列
     * {@code session_id / msg_index / block_index / role / kind / tool_name / tool_use_id / saved_at}。
     * tokenize 用 {@code unicode61 remove_diacritics 2}(Hermes 同款,中文按字切英文不词干)。
     */
    @Data
    public static class Search {
        /**
         * SQLite 数据库文件名,放在 {@link com.xilidou.jooj.bootstrap.JoojHome} 下。
         * 默认 {@code search.db}。
         */
        private String dbFilename = "search.db";

        /**
         * Schema 版本 —— SearchStore.ensureSchema 启动期校验
         * {@code schema_meta.version},不匹配走 startupCheck 策略。
         * 改 schema 时 +1。
         */
        private int schemaVersion = 1;

        /** session_search tool 默认 limit(LLM 不传 limit 时使用)。 */
        private int defaultLimit = 10;

        /** session_search tool 最大 limit clamp(防 LLM 传超大值压垮 LLM 输出)。 */
        private int maxLimit = 50;

        /** SQLite busy timeout(毫秒)—— 写并发时其他连接等的最长时间。WAL 模式下基本用不到。 */
        private int busyTimeoutMs = 5000;

        /**
         * 启动期一致性检查模式:
         * <ul>
         *   <li>{@code none} — 不查,直接用现有 db</li>
         *   <li>{@code light}(默认)— 查 schema_meta.version,不一致 → drop + recreate(空索引,**不自动重建数据**),log warn 提示用户必要时调 rebuildAll API</li>
         *   <li>{@code strict} — 遍历所有 session 对 countSession(sid) vs JSON 中可索引 message 数</li>
         * </ul>
         */
        private String startupCheck = "light";
    }
}
