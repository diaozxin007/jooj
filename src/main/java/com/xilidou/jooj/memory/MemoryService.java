package com.xilidou.jooj.memory;

import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.MessageParam;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Memory 系统的 Facade —— 全局共享(Demo 13 修正:回到 1-user 假设)。
 *
 * <h3>设计立场</h3>
 *
 * <p>jooj 服务**单 operator**(personal AI assistant 定位,跟 OpenClaw 一致)。
 * 用户记忆("operator 喜欢 Python"、"alice 是同事生日 6 月 1 日"、"项目 X 用 Spring")
 * 应该跨所有 conversation 共享 —— 这是 feature 不是 bug:
 *
 * <p>operator 在跟 alice 微信聊完后切到 web session 问 jooj "alice 生日是哪天",
 * jooj 应该能答出来。如果 memory per-session 隔离,这种跨对话查询直接失效。
 *
 * <h3>跟 conversation context 的区分</h3>
 *
 * <p>**per-session(已隔离的)**:history、todo、bg notification —— 都是
 * 这条对话当下的语境,跟别的对话混会让 LLM 无所适从。
 *
 * <p>**全局共享(本类)**:user 长期事实、preferences、关于 contacts 和 world
 * 的知识 —— 跟具体对话无关,operator 在哪条对话里都该看到。
 *
 * <h3>历史</h3>
 *
 * <p>Demo 12 错把 memory 也 per-session 化了,基于"多 user IM gateway"的错误假设。
 * 实际 jooj 跟 OpenClaw 一样是 1-user personal assistant,IM 里的 alice/bob 是
 * operator 的 contacts 而非 jooj 的 users。Demo 13 撤销该改动。
 */
@Slf4j
public class MemoryService {

    private final MemoryStore store;
    private final MemorySelector selector;
    private final MemoryExtractor extractor;
    private final MemoryConsolidator consolidator;
    private final MemoryConfig config;

    /**
     * s21 Demo 26 / Hermes Tier 3 P3.1:Background reviewer + 异步执行池。
     * 都可空(没装时 onTurnEnd 不调 review,主路径不变)。
     */
    private final BackgroundReviewer reviewer;
    private final java.util.concurrent.Executor reviewExecutor;

    public MemoryService() {
        this(new MemoryConfig(), null, null);
    }

    /**
     * 老 3 参 ctor —— 不接 BackgroundReviewer,等价旧行为。测试 / 不需要 review 的场景走这条。
     */
    public MemoryService(MemoryConfig config, AnthropicClient client, String model) {
        this(config, client, model, null, null);
    }

    /**
     * 5 参 ctor —— 接 BackgroundReviewer + 异步 executor。{@code reviewer} / {@code reviewExecutor}
     * 任一为 null,onTurnEnd 都跳过 review(主路径仍正常)。
     *
     * <p>典型生产装配在 {@link MemoryConfiguration} 里:Spring 注入 {@code joojBgExecutor}
     * + new BackgroundReviewer(store, client, model)。
     */
    public MemoryService(MemoryConfig config, AnthropicClient client, String model,
                         BackgroundReviewer reviewer,
                         java.util.concurrent.Executor reviewExecutor) {
        this.config = config;
        this.store = new MemoryStore(config);
        this.selector = new MemorySelector(store, client, model);
        this.extractor = new MemoryExtractor(store, client, model);
        this.consolidator = new MemoryConsolidator(store, config, client, model);
        this.reviewer = reviewer;
        this.reviewExecutor = reviewExecutor;
    }

    /**
     * Turn 开始前调用 —— 选取当前 messages 相关的 memory,拼成注入文本。
     *
     * <p>返回空字符串表示无相关 memory(SYSTEM 不需要追加额外内容)。
     */
    public String loadRelevant(List<MessageParam> messages) {
        try {
            return selector.load(messages);
        } catch (Exception e) {
            log.warn("[Memory] loadRelevant failed: {}", e.toString());
            return "";
        }
    }

    /**
     * Turn 结束后调用 —— 抽取本轮新事实落盘 + 必要时合并旧 memory。
     *
     * <p>s21 Demo 26 / Hermes Tier 3 P3.1:接背景 reviewer 异步触发 ——
     * Extractor + Consolidator 仍同步跑(主路径,LLM 下一轮立即看到新 fact),
     * Reviewer 在 BgExecutor 里**异步**跑(turn 不阻塞,replay 找重复模式 / 工作流教训)。
     *
     * <p>三者职责互补:
     * <ul>
     *   <li>Extractor —— 抽本轮 fact("用户偏好 tabs")</li>
     *   <li>Consolidator —— memory 文件数超阈值合并旧 entry</li>
     *   <li>Reviewer —— 找跨 turn 模式("用户两次纠正我用 ripgrep 不要用 grep")</li>
     * </ul>
     */
    public void onTurnEnd(List<MessageParam> messages) {
        try {
            extractor.extract(messages);
        } catch (Exception e) {
            log.warn("[Memory] extract failed: {}", e.toString());
        }
        try {
            consolidator.consolidate();
        } catch (Exception e) {
            log.warn("[Memory] consolidate failed: {}", e.toString());
        }
        // s21 Demo 26:异步 background review。null 安全,任一组件没装就跳过。
        if (reviewer != null && reviewExecutor != null && messages != null && !messages.isEmpty()) {
            // 拷一份 immutable snapshot:reviewer 在另一个线程跑,主线程后续可能再 mutate messages
            // (Demo 25 副作用 v3 的 send-time scrub 等),不能让两边竞争同一个 list
            final List<MessageParam> snapshot = List.copyOf(messages);
            try {
                reviewExecutor.execute(() -> {
                    try {
                        reviewer.review(snapshot);
                    } catch (Throwable t) {
                        // 双层 try:executor 内部任何异常都吞,不让 bg 线程死
                        log.warn("[Memory:Review] async review threw: {}", t.toString());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 池满 + AbortPolicy 才会触发;BgExecutor 用 CallerRunsPolicy 不会到这里,
                // 但万一未来换 executor 仍兜底 —— review 不能挡 onTurnEnd 主路径
                log.warn("[Memory:Review] executor rejected, skipping review: {}", e.toString());
            }
        }
    }

    /**
     * Pre-compression 抢救入口(s21 Demo 24 / P2.2)—— L4 触发前调,只跑 extract 不跑 consolidate。
     *
     * <p>跟 {@link #onTurnEnd} 区别:
     * <ul>
     *   <li>{@code onTurnEnd}:每轮跑 extract + consolidate(成本高,不能在 L4 危机时刻再跑 consolidate)</li>
     *   <li>{@code preCompressionExtract}:只跑 extract(消耗 1 次 LLM 调用),抢救永久 fact 后让 L4 摘要</li>
     * </ul>
     *
     * <p>失败时静默 warn,**不抛**(L4 是危机救命路径,extract 失败不该挡住摘要)。
     *
     * <p>对应 ByteRover memory provider 的"automatic pre-compression extraction" 设计。
     */
    public void preCompressionExtract(List<MessageParam> messages) {
        try {
            extractor.extract(messages);
        } catch (Exception e) {
            log.warn("[Memory] pre-compression extract failed: {}", e.toString());
        }
    }

    /**
     * 给 SystemPromptAssembler 用 —— 当前所有 memory 的索引文本(raw,Markdown 链接列表)。
     *
     * <p>SidebarController 也用这个,直接渲染成 Markdown 链接给前端 panel。
     */
    public String catalog() {
        try {
            return store.readIndex();
        } catch (Exception e) {
            log.warn("[Memory] catalog read failed: {}", e.toString());
            return "";
        }
    }

    /**
     * 专门给 SystemPromptAssembler / LLM context 用的 catalog 渲染(s21 Demo 21 + 22)。
     *
     * <p>跟 {@link #catalog()} 的区别 —— 这版加了 § 分隔符 + 容量百分比头 +
     * <b>按 type 分组</b>(s21 Demo 22),让 LLM 区分"用户偏好 / 工作流教训 /
     * 项目事实 / 引用指针"语境。
     *
     * <p>对照 Hermes 的 MEMORY.md / USER.md 双文件设计:
     * <ul>
     *   <li>Hermes:**两个文件**,user 偏好和环境事实物理隔离</li>
     *   <li>jooj:**一个目录 + 4 个 type 字段**,catalog 渲染时按 type 分段
     *       —— 物理上一个目录,但 LLM 看到的是分组的</li>
     * </ul>
     *
     * <p>format:
     * <pre>
     * [Memory  892/20000 chars (4%)]
     *
     * User preferences:
     * § user-tabs (user-tabs.md) — User prefers tabs
     *
     * Workflow lessons:
     * § feedback-no-mock-db (feedback-no-mock-db.md) — Don't mock the database
     *
     * Project facts:
     * § project-x-uses-spring (project-x-uses-spring.md) — Project X uses Spring
     *
     * Reference pointers:
     * § linear-ingest (linear-ingest.md) — Pipeline bugs are in Linear INGEST
     * </pre>
     *
     * <p>对应 Hermes 的同类设计:
     * <pre>
     * [MEMORY.md  892/2200 chars (40%)]
     * §  Server runs Ubuntu 22.04, prefer apt over snap
     * §  Python projects use uv, not pip
     * </pre>
     *
     * <p>为什么单独一个 method 而不是改 {@link #catalog()}:
     * <ul>
     *   <li>SidebarController 把 catalog 当 Markdown 渲染给前端 panel —— 那里需要
     *       原始 {@code - [name](file) — desc} 格式才能显示链接</li>
     *   <li>LLM 看到 § 风格 + 配额头 + type 分组才能精准 GC + 区分语境,但 panel 用户看不到也不需要</li>
     *   <li>分两个 renderer 互不干扰,职责清晰</li>
     * </ul>
     *
     * <p>空 catalog → 返回空字符串(SystemPromptAssembler 跳过整段 memory section)。
     * 某个 type 无 entry → 那段标题完全不出现(不留空段)。
     */
    public String catalogForSystemPrompt() {
        List<MemoryFile> all;
        try {
            all = store.list();
        } catch (Exception e) {
            log.warn("[Memory] catalogForSystemPrompt list failed: {}", e.toString());
            return "";
        }
        if (all.isEmpty()) return "";

        int used = totalBodyChars();
        int limit = config.totalMaxBytes();
        int pct = limit > 0 ? (int) Math.round(used * 100.0 / limit) : 0;

        // 按 type 分组,每组内按 name 字典序
        Map<MemoryFile.Type, List<MemoryFile>> byType = new EnumMap<>(MemoryFile.Type.class);
        for (MemoryFile m : all) {
            if (m.getName() == null || m.getFilename() == null) continue;
            MemoryFile.Type t = m.getType() == null ? MemoryFile.Type.USER : m.getType();
            byType.computeIfAbsent(t, k -> new ArrayList<>()).add(m);
        }
        for (List<MemoryFile> group : byType.values()) {
            group.sort(Comparator.comparing(MemoryFile::getName, Comparator.nullsLast(String::compareTo)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[Memory  %d/%d chars (%d%%)]%n", used, limit, pct));

        // 4 个 type 按固定顺序输出(USER 偏好 → FEEDBACK 教训 → PROJECT 事实 → REFERENCE 指针)
        for (MemoryFile.Type t : MemoryFile.Type.values()) {
            List<MemoryFile> group = byType.get(t);
            if (group == null || group.isEmpty()) continue;
            sb.append('\n').append(typeHeader(t)).append('\n');
            for (MemoryFile m : group) {
                sb.append("§ ").append(m.getName()).append(" (").append(m.getFilename()).append(")");
                String desc = m.getDescription();
                if (desc != null && !desc.isBlank()) {
                    sb.append(" — ").append(desc);
                }
                sb.append('\n');
            }
        }

        // 去尾换行(SystemPromptAssembler 拼接时控制 delimiter)
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * type → LLM 可读的语义标题(s21 Demo 22)。
     *
     * <p>命名对应 MemoryFile.Type 注释里"各自回答不同的问题":
     * <ul>
     *   <li>USER → 谁是用户的偏好 → "User preferences"</li>
     *   <li>FEEDBACK → 怎么干活的教训 → "Workflow lessons"</li>
     *   <li>PROJECT → 正在发生什么 → "Project facts"</li>
     *   <li>REFERENCE → 东西在哪 → "Reference pointers"</li>
     * </ul>
     */
    static String typeHeader(MemoryFile.Type t) {
        return switch (t) {
            case USER      -> "User preferences:";
            case FEEDBACK  -> "Workflow lessons:";
            case PROJECT   -> "Project facts:";
            case REFERENCE -> "Reference pointers:";
        };
    }

    /** 当前总 body 字符数(s21 Demo 21,容量配额计量)。*/
    public int totalBodyChars() {
        try {
            return store.totalBodyChars();
        } catch (Exception e) {
            log.warn("[Memory] totalBodyChars failed: {}", e.toString());
            return 0;
        }
    }

    /** 配额上限(s21 Demo 21)。*/
    public int totalMaxBytes() {
        return config.totalMaxBytes();
    }

    /** 测试用:取底层 store。 */
    MemoryStore store() {
        return store;
    }
}
