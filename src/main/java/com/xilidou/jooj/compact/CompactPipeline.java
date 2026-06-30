package com.xilidou.jooj.compact;

import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.memory.MemoryService;

import java.util.List;
import java.util.Objects;

/**
 * 上下文压缩流水线 —— Week 6 / s08(L1+L2+L3+L4 四层压缩)。
 *
 * <p>对应 Python {@code learn-claude-code} s08 的 compact pipeline。
 * 教学版顺序: budget(L3) → snip(L1) → micro(L2) (proactive,每轮跑) +
 * compact_history(L4) (reactive,只在 prompt_too_long 时跑)。
 *
 * <p>两个入口分开:
 * <ul>
 *   <li>{@link #apply(List)} —— proactive,L3+L1+L2,每轮 LLM 调用前主动跑,
 *       不消耗 API token</li>
 *   <li>{@link #reactiveCompact(List)} —— reactive,L4,只在
 *       {@link com.xilidou.jooj.http.AnthropicException#isPromptTooLong()}
 *       时调用,消耗 API token(LLM 摘要)</li>
 * </ul>
 *
 * <p>顺序关键(apply):
 * <ul>
 *   <li>L3 (budget) 先跑:把超大 tool_result 落盘 + 替换为短 stub,
 *       让 L1/L2 后续看到的 messages 已经"瘦身"</li>
 *   <li>L1 (snip) 再跑:看条数,削掉中间过期消息</li>
 *   <li>L2 (micro) 最后跑:把剩下的旧 tool_result(中等大小)替换为占位符</li>
 * </ul>
 *
 * <p>设计要点:
 * <ul>
 *   <li>单一入口:{@link #apply(List)} 内部决定跑哪几层。AgentLoop 只插一行,
 *       未来加 L5 不改 AgentLoop</li>
 *   <li>L4 单独 reactive 入口:不混在 apply 里,因为它消耗 API token,
 *       不该每轮都跑</li>
 *   <li>L4 客户端注入是 optional:测试和无 LLM 场景下 client=null 时 reactiveCompact
 *       直接返回 false,不抛异常</li>
 *   <li>不复用 Hook 系统:Hook 的 4 个事件(UserPromptSubmit/PreToolUse/PostToolUse/Stop)
 *       都不在"LLM 调用前"或"400 错误后"这两个时机点</li>
 * </ul>
 *
 * <p>切片 C 之后:本类**保持 framework-agnostic**(不加 @Component),
 * 由 {@link com.xilidou.jooj.compact.CompactConfiguration#compactPipeline(CompactConfig,
 * com.xilidou.jooj.http.AnthropicClient, com.xilidou.jooj.JoojProperties)}
 * 通过 {@code @Bean} 装配。
 */
public class CompactPipeline {

    private final BudgetCompactor budget;
    private final SnipCompactor snip;
    private final MicroCompactor micro;
    /** L4 是 reactive 的,client=null 时不可用。*/
    private final HistoryCompactor history;
    /**
     * Pre-compression extraction(s21 Demo 24 / P2.2)—— L4 触发前先抢救永久 fact。
     * <p>可空(纯 CLI / 老配置 / 无 client 时不启用),null 时 reactiveCompact 跳过抢救阶段。
     */
    private final MemoryService memoryService;

    /** 默认配置 + 无 L4(client=null,reactive 不可用)。*/
    public CompactPipeline() {
        this(new CompactConfig(), null, null, null);
    }

    /** L1+L2+L3 配置(L4 不可用,常用于测试)。*/
    public CompactPipeline(CompactConfig config) {
        this(config, null, null, null);
    }

    /**
     * L1+L2+L3+L4 配置,**无 pre-compression extraction**(向后兼容,Demo 24 之前的 ctor 签名)。
     */
    public CompactPipeline(CompactConfig config, AnthropicClient client, String model) {
        this(config, client, model, null);
    }

    /**
     * 完整构造器:L1+L2+L3+L4 + 可选 pre-compression extraction。
     *
     * @param config        配置
     * @param client        LLM 客户端(L4 摘要用),null = 禁用 L4
     * @param model         L4 摘要用模型(client 非 null 时必填)
     * @param memoryService 可选,L4 触发前抢救永久 fact 用 —— null 时跳过抢救阶段
     */
    public CompactPipeline(CompactConfig config, AnthropicClient client, String model,
                           MemoryService memoryService) {
        Objects.requireNonNull(config, "config");
        this.budget = new BudgetCompactor(config);
        this.snip = new SnipCompactor(config);
        this.micro = new MicroCompactor(config);
        if (client != null) {
            Objects.requireNonNull(model, "model required when client provided");
            this.history = new HistoryCompactor(config, client, model);
        } else {
            this.history = null;
        }
        this.memoryService = memoryService;
    }

    /**
     * 跑完 L3 + L1 + L2(proactive,不消耗 API token)。
     *
     * @param messages 对话历史(可能被原地修改)
     * @return 是否至少触发了一层压缩
     */
    public boolean apply(List<MessageParam> messages) {
        boolean changed = false;
        changed |= budget.apply(messages);
        changed |= snip.apply(messages);
        changed |= micro.apply(messages);
        return changed;
    }

    /**
     * L4 reactive 摘要(消耗 API token)。
     *
     * <p>调用方:{@link com.xilidou.jooj.agent.AgentLoopHarness} 在收到
     * {@link com.xilidou.jooj.http.AnthropicException#isPromptTooLong()}
     * 时调用此方法,然后重试 LLM 请求。
     *
     * <p>L4 不可用(client=null)或失败时返回 false——调用方应该把原 400 错误
     * 重新抛出,而不是无限循环。
     *
     * <p><b>s21 Demo 24 / P2.2:pre-compression extraction</b>
     * 在 L4 摘要 *之前* 先调 {@link MemoryExtractor#extract},把 messages 中
     * "值得永久记的事实"先抢救进 MEMORY.md。这跟 onTurnEnd 的 extract 区别:
     * <ul>
     *   <li>onTurnEnd extract:每轮自然停顿点都跑,捕捉持续状态变化(每轮成本)</li>
     *   <li>pre-compression extract:只在 L4 触发(危机时刻)才跑,
     *       抢救即将被摘要 lossy 替换的对话内容(单次成本但价值高 —— 即将被丢)</li>
     * </ul>
     *
     * <p>对应 ByteRover memory provider 的设计:
     * <blockquote>"Automatic pre-compression extraction (saves insights before context
     * compression discards them)."</blockquote>
     *
     * <p>Extractor 失败时跳过抢救阶段直接走 L4 —— 抢救是锦上添花,L4 才是主救命路径,
     * extractor 不该挡住 L4。
     *
     * @param messages 对话历史
     * @return 是否成功摘要
     */
    public boolean reactiveCompact(List<MessageParam> messages) {
        if (history == null) {
            return false;
        }
        // s21 Demo 24:抢救阶段(锦上添花,失败不挡 L4)
        // 双层防御 —— MemoryService.preCompressionExtract 内部已 try/catch warn,
        // 这里再裹一层防"service 实现变 contract 时 pipeline 不连带挂掉"。
        // L4 是 prompt_too_long 危机救命路径,extract 失败必须不挡 summary 继续走。
        if (memoryService != null) {
            try {
                memoryService.preCompressionExtract(messages);
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger(CompactPipeline.class)
                        .warn("[Compact L4] pre-compression extract threw, proceeding to summary: {}",
                                t.toString());
            }
        }
        return history.apply(messages);
    }

    /** 测试用:是否启用了 L4(client 已注入)。*/
    public boolean hasReactiveSupport() {
        return history != null;
    }

    /** 测试用:是否启用了 pre-compression extraction(s21 Demo 24)。*/
    public boolean hasPreCompressionExtraction() {
        return memoryService != null;
    }
}
