package com.xilidou.jooj.eval.agent;

import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.Usage;

import java.util.List;
import java.util.Objects;

/**
 * 基于 {@link AnthropicClient} 的单轮 {@link AgentInvoker}。
 *
 * <p>为什么是"单轮":Week11 Task 1 只要把 4 种 Scorer 跑起来看效果,
 * 不需要 tool loop 也不需要多轮上下文。将来接
 * {@code com.xilidou.jooj.agent.AgentLoopHarness}(完整 Agent)是**另一个**
 * {@link AgentInvoker} 实现,不影响本类。
 *
 * <p>关键设计:
 * <ul>
 *   <li><b>system prompt 由构造函数注入</b> —— v1 直接传 {@code null}
 *       (裸问模型),v2 传"careful, never fabricate..."之类的引导语。
 *       同一批 golden case 跑两遍就能对比 system prompt 效果</li>
 *   <li><b>异常兜底</b> —— {@link AnthropicClient} 抛的
 *       {@code AnthropicException}、IO 异常、序列化异常等一律转
 *       {@link AgentInvocation#errorReason},不向上传递</li>
 *   <li><b>模型 / max_tokens 由外部注入</b> —— 复用 {@code jooj.anthropic.model}
 *       和 {@code jooj.recovery.default-max-tokens} yml 配置或调用方指定,
 *       Invoker 不知道也不需要知道路由到 Anthropic 还是 DeepSeek
 *       (那是 {@link com.xilidou.jooj.http.ModelRouter} 的活)</li>
 * </ul>
 */
public class AnthropicAgentInvoker implements AgentInvoker {

    private final AnthropicClient client;
    private final String model;
    private final int maxTokens;
    /** 可空。null 表示不带 system 段,直接把 user 消息发给模型。 */
    private final String systemPrompt;

    public AnthropicAgentInvoker(AnthropicClient client, String model, int maxTokens,
                                 String systemPrompt) {
        this.client = Objects.requireNonNull(client, "client");
        this.model = Objects.requireNonNull(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0, got " + maxTokens);
        }
        this.maxTokens = maxTokens;
        this.systemPrompt = systemPrompt;   // 允许 null
    }

    @Override
    public AgentInvocation invoke(String userInput) {
        long t0 = System.currentTimeMillis();
        try {
            CreateMessageRequest req = CreateMessageRequest.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    // system 允许 null:CreateMessageRequest 用 @JsonInclude(NON_NULL),
                    // null 时字段不出现在 JSON 里,Anthropic 会当作"没有 system"处理
                    .system(systemPrompt)
                    .messages(List.of(MessageParam.user(userInput)))
                    .build();

            CreateMessageResponse resp = client.createMessage(req);
            long dt = System.currentTimeMillis() - t0;

            Usage u = resp == null ? null : resp.getUsage();
            String text = resp == null ? "" : resp.firstText();
            int inTok = u == null ? 0 : u.getInputTokens();
            int outTok = u == null ? 0 : u.getOutputTokens();

            return new AgentInvocation(text, inTok, outTok, dt, null);
        } catch (Exception ex) {
            long dt = System.currentTimeMillis() - t0;
            // 保留与 BenchmarkRunner 老行为一致的错误格式,方便 Scorer 打分时
            // 能看到"这不是模型的正常输出",负例场景也不会误判为通过
            String label = "<AGENT_ERROR: " + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage() + ">";
            return new AgentInvocation(label, 0, 0, dt, safeMessage(ex));
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }
}
