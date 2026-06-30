package com.xilidou.jooj.memory;

import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.MessageParam;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

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

    public MemoryService() {
        this(new MemoryConfig(), null, null);
    }

    public MemoryService(MemoryConfig config, AnthropicClient client, String model) {
        this.config = config;
        this.store = new MemoryStore(config);
        this.selector = new MemorySelector(store, client, model);
        this.extractor = new MemoryExtractor(store, client, model);
        this.consolidator = new MemoryConsolidator(store, config, client, model);
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
     * 专门给 SystemPromptAssembler / LLM context 用的 catalog 渲染(s21 Demo 21)。
     *
     * <p>跟 {@link #catalog()} 的区别 —— 这版加了 §  分隔符 + 容量百分比头,
     * 让 LLM 看见 "[Memory N/M chars (P%)]" 知道还剩多少配额,主动 GC。
     *
     * <p>对照 Hermes 的同类设计:
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
     *   <li>LLM 看到 §  风格 + 配额头才能主动 GC,但 panel 用户看不到也不需要</li>
     *   <li>分两个 renderer 互不干扰,职责清晰</li>
     * </ul>
     *
     * <p>空 catalog → 返回空字符串(SystemPromptAssembler 跳过整段 memory section)。
     */
    public String catalogForSystemPrompt() {
        String raw;
        try {
            raw = store.readIndex();
        } catch (Exception e) {
            log.warn("[Memory] catalogForSystemPrompt read failed: {}", e.toString());
            return "";
        }
        if (raw == null || raw.isBlank()) return "";

        int used = totalBodyChars();
        int limit = config.totalMaxBytes();
        int pct = limit > 0 ? (int) Math.round(used * 100.0 / limit) : 0;

        // 行格式:把 raw catalog 的 "- [name](file) — desc" 改成 "§  name (file) — desc"
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[Memory  %d/%d chars (%d%%)]%n", used, limit, pct));
        for (String line : raw.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            String reformatted = reformatCatalogLine(line);
            sb.append(reformatted).append('\n');
        }
        // 去尾换行,避免 SystemPromptAssembler 拼接后多一空行
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 把 raw catalog 行(MarkDown 链接格式)改成 §  风格,失败返回原行。
     *
     * <p>raw 行如:{@code - [user-preference-tabs](user-preference-tabs.md) — User prefers tabs}
     * <br>转后:{@code §  user-preference-tabs (user-preference-tabs.md) — User prefers tabs}
     *
     * <p>解析失败(LLM 写出非标准格式 / index 损坏)按原样保留,不让 LLM 看见 jooj 内部的格式假设。
     */
    static String reformatCatalogLine(String line) {
        // 匹配 "- [name](file) — desc"
        // 注意 — 是 em dash (U+2014),不是 hyphen
        java.util.regex.Matcher m = CATALOG_LINE_PATTERN.matcher(line);
        if (!m.matches()) return line;
        String name = m.group(1);
        String file = m.group(2);
        String desc = m.group(3);
        return "§  " + name + " (" + file + ")" + (desc.isBlank() ? "" : " — " + desc);
    }

    private static final java.util.regex.Pattern CATALOG_LINE_PATTERN =
            java.util.regex.Pattern.compile("^\\s*-\\s*\\[([^\\]]+)\\]\\(([^)]+)\\)\\s*—?\\s*(.*)$");

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
