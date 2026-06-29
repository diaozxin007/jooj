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

    public MemoryService() {
        this(new MemoryConfig(), null, null);
    }

    public MemoryService(MemoryConfig config, AnthropicClient client, String model) {
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
     * 给 SystemPromptAssembler 用 —— 当前所有 memory 的索引文本。
     */
    public String catalog() {
        try {
            return store.readIndex();
        } catch (Exception e) {
            log.warn("[Memory] catalog read failed: {}", e.toString());
            return "";
        }
    }

    /** 测试用:取底层 store。 */
    MemoryStore store() {
        return store;
    }
}
