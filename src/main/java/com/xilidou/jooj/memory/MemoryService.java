package com.xilidou.jooj.memory;

import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.MessageParam;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Memory 系统的 Facade —— 把 4 个子系统封装成单一 API, 给 AgentLoop 用。
 *
 * <p>对 AgentLoop 暴露 3 个方法 + 1 个常驻字段:
 * <ul>
 *   <li>{@link #loadRelevant(List)} — turn 开始时注入相关 memory 到 user message</li>
 *   <li>{@link #onTurnEnd(List)} — turn 结束(stop_reason != tool_use)时
 *       触发 extract + consolidate</li>
 *   <li>{@link #catalog()} — 返回 MEMORY.md 索引内容, SYSTEM prompt 拼装时用</li>
 * </ul>
 *
 * <p>设计理由:
 * <ul>
 *   <li>AgentLoopHarness 已经 700+ 行, 不该再持有 4 个 memory 子系统的引用</li>
 *   <li>Facade 让 AgentLoop 只看到"memory 该做什么", 不看"怎么做"</li>
 *   <li>未来扩展子系统(比如加 Async extraction)只动 Facade 内部</li>
 * </ul>
 *
 * <p>客户端 / 模型可空:
 * <ul>
 *   <li>{@code client = null} → Selector 走关键词回退,
 *       Extractor / Consolidator 禁用</li>
 *   <li>这让测试场景能用 null client + 已有 memory 验证 Selection 关键词路径</li>
 *   <li>生产场景 Spring 注入真 client, 4 个子系统全启用</li>
 * </ul>
 *
 * <p>切片 C 之后:本类**保持 framework-agnostic**(不加 @Component),
 * 由 {@link com.xilidou.jooj.memory.MemoryConfiguration#memoryService(MemoryConfig,
 * com.xilidou.jooj.http.AnthropicClient, com.xilidou.jooj.JoojProperties)}
 * 通过 {@code @Bean} 装配。
 */
@Slf4j
public class MemoryService {

    private final MemoryStore store;
    private final MemorySelector selector;
    private final MemoryExtractor extractor;
    private final MemoryConsolidator consolidator;

    /**
     * 默认配置 + 无 client(关键词回退路径,生产基本不会用)。
     */
    public MemoryService() {
        this(new MemoryConfig(), null, null);
    }

    /**
     * 完整构造器。
     *
     * @param config Memory 配置
     * @param client LLM 客户端,null = LLM 子系统降级(Selector 走关键词,
     *               Extractor/Consolidator 禁用)
     * @param model  LLM 模型 ID(client 非 null 时必填)
     */
    public MemoryService(MemoryConfig config, AnthropicClient client, String model) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.store = new MemoryStore(config);
        this.selector = new MemorySelector(store, client, model);
        this.extractor = new MemoryExtractor(store, client, model);
        this.consolidator = new MemoryConsolidator(store, config, client, model);
    }

    // ─────────────────────────────────────────────────────────────
    //  AgentLoop 介入点 1:turn 开始, 注入相关 memory
    // ─────────────────────────────────────────────────────────────

    /**
     * 给定当前对话, 选出语义相关的 memory 并渲染成可注入字符串。
     *
     * <p>调用方(AgentLoop):
     * <pre>
     *   String injection = memoryService.loadRelevant(messages);  // 此时不含新 query
     *   String enriched = injection.isBlank() ? query : injection + "\n\n" + query;
     *   messages.add(MessageParam.user(enriched));
     * </pre>
     *
     * <p>无相关 memory 时返回空字符串。永远不抛异常。
     */
    public String loadRelevant(List<MessageParam> messages) {
        try {
            return selector.load(messages);
        } catch (Exception e) {
            log.warn("[Memory] loadRelevant failed: {}", e.toString());
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  AgentLoop 介入点 2:turn 结束(stop_reason != tool_use)
    // ─────────────────────────────────────────────────────────────

    /**
     * 自然停顿点触发:从对话提取新 fact, 然后视情况整理已有 memory。
     *
     * <p>调用顺序:
     * <ol>
     *   <li>{@link MemoryExtractor#extract} 从对话提取新 fact 写盘</li>
     *   <li>{@link MemoryConsolidator#consolidate} 文件数 ≥ 阈值时去重合并</li>
     * </ol>
     *
     * <p>**先 extract 后 consolidate** 的理由:
     * <ul>
     *   <li>extract 可能让 memory 总数超过 consolidate 阈值, 接着跑 consolidate
     *       能立刻把它压回去</li>
     *   <li>反过来 consolidate 先跑, 看到的 memory 总数还没增加, 可能错过触发时机</li>
     * </ul>
     *
     * <p>失败优雅降级:任何子系统抛异常都被 catch, 不传给 AgentLoop。
     * Memory 系统 ≠ 关键路径, 不该让它的 bug 让用户的对话退出。
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

    // ─────────────────────────────────────────────────────────────
    //  SYSTEM prompt 注入用
    // ─────────────────────────────────────────────────────────────

    /**
     * 返回 MEMORY.md 索引内容(每行一条 memory 的 name + description)。
     *
     * <p>调用方:fromEnv() 拼 SYSTEM prompt 时拼进去, 让模型从一开始就知道
     * ".memory/ 里有哪些条目"——即使具体 body 没注入, 索引让模型知道 catalog 视角。
     *
     * <p>无 memory 时返回空字符串。
     */
    public String catalog() {
        try {
            return store.readIndex();
        } catch (Exception e) {
            log.warn("[Memory] catalog read failed: {}", e.toString());
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Test-only access(包级可见,给 MemoryServiceTest 用)
    // ─────────────────────────────────────────────────────────────

    MemoryStore store() {
        return store;
    }
}
