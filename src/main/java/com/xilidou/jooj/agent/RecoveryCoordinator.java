package com.xilidou.jooj.agent;

import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.compact.CompactPipeline;
import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.AnthropicException;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.MessageParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Error Recovery 协调器(s11)。把"调用 LLM + 处理三条恢复路径"封装成单一入口
 * {@link #call},让 {@link AgentLoopHarness#agentLoop} 不再为错误恢复继续膨胀。
 *
 * <h3>三条恢复路径(对应 Python 原版 s11)</h3>
 *
 * <ul>
 *   <li><b>Path 1</b>(max_tokens 截断):{@code stop_reason == "max_tokens"} 时,
 *       第一次升级 max_tokens(default → escalated,**不 append** 截断输出);
 *       仍截断时 append + 注入 continuation prompt,最多 {@code maxRecoveryRetries} 次</li>
 *   <li><b>Path 2</b>(prompt_too_long):{@link AnthropicException#isPromptTooLong} 时,
 *       触发 {@link CompactPipeline#reactiveCompact}(LLM 摘要历史)+ 重试一次;不行抛</li>
 *   <li><b>Path 3</b>(429/529):
 *       {@link #withRetry} 用指数退避 + 抖动重试 {@code maxRetries} 次;
 *       连续 {@code maxConsecutive529} 次 529 后切到 {@code fallbackModel}</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 *
 * <ul>
 *   <li><b>per-call state</b>:每次 {@link #call} 由调用方(agentLoop)传入
 *       {@link RecoveryState},保证跨 loop 调用不污染</li>
 *   <li><b>requestBuilder 是 lambda</b>:retry 中 model / max_tokens 可能被 mutate,
 *       request 必须每次重建。lambda 接 RecoveryState 让构建逻辑读到最新状态</li>
 *   <li><b>messages 原地修改</b>:reactive compact 内部对 messages list 原地裁剪,
 *       coordinator 不需要返回新 list,Path 2 直接重试就拿到新 messages</li>
 *   <li><b>硬编码三条路径</b>:不抽 Strategy 接口。Path 1 在 stop_reason 后触发,
 *       Path 2/3 在 exception 后触发,统一接口反而别扭。简单直接</li>
 * </ul>
 */
@Component
@Slf4j
public class RecoveryCoordinator {

    private final AnthropicClient client;
    private final String defaultModel;
    private final JoojProperties.Recovery cfg;
    private final CompactPipeline compactPipeline;
    private final Random random = new Random();

    public RecoveryCoordinator(JoojProperties props, CompactPipeline compactPipeline,
                               AnthropicClient client) {
        this.client = client;
        this.defaultModel = props.getAnthropic().getModel();
        this.cfg = props.getRecovery();
        this.compactPipeline = compactPipeline;

        // 启动期可见配置,避免 fallback 配错(打错字、忘配)在生产才暴露。
        if (StringUtils.hasText(cfg.getFallbackModel())) {
            log.info("[Recovery] fallback model configured: {}", cfg.getFallbackModel());
        }
    }

    /**
     * s22 架构审查修复:创建 per-turn RecoveryState —— 用 coordinator 已知的默认 model
     * 和 max_tokens 初始化,让 caller (AgentLoopHarness) 不再需要持有这两个配置字段。
     */
    public RecoveryState newState() {
        return new RecoveryState(defaultModel, cfg.getDefaultMaxTokens());
    }

    /**
     * 调用 LLM + 处理三条恢复路径,内部循环直到拿到可用 response 或 fatal。
     *
     * <p>s22 架构审查(2026-07-13,B2 refactor):call 现在**只暴露成功 / 失败二元结局**
     * ——caller (agentLoop) 拿到 response 就继续,拿到 FatalRecoveryException 就 append
     * error 结束 turn。原来的 4-branch {@code RecoveryResult} sealed type 被删除:
     * EscalateAndRetry 和 AppendContinuation 是 recovery **内部**决定"再试一次"的实现细节,
     * caller 不该关心。
     *
     * <h3>Recovery 内部循环</h3>
     *
     * <p>三种 retry 场景现在都在本方法内部消化:
     * <ul>
     *   <li>Path 1 max_tokens 首次:升级 currentMaxTokens 后 continue</li>
     *   <li>Path 1 max_tokens 后续:append truncated assistant + append user continuation prompt,continue</li>
     *   <li>Path 2 prompt_too_long:reactiveCompact + continue</li>
     * </ul>
     *
     * <p>Path 3(429/529)在 {@link #withRetry} 里处理(已本身就是内部循环)。
     *
     * <h3>Messages mutation 语义</h3>
     *
     * <p>Recovery **有权** mutate messages —— Path 2 之前就有 reactiveCompact,B2 把
     * Path 1 continuation 的 mutation 也搬进来,语义一致。Caller 不需要知道 messages 是不是被改过。
     *
     * @param requestBuilder  接 RecoveryState 返回 request。state 每次改动后重建
     * @param messages        对话历史。**Path 1 continuation / Path 2 compact 会原地修改**
     * @param state           per-agent-loop 状态机(由 {@link #newState()} 创建)
     * @return 成功的 LLM response(保证 stop_reason != max_tokens)
     * @throws FatalRecoveryException 三条路径都尝试过后仍无法恢复
     */
    public CreateMessageResponse call(
            Function<RecoveryState, CreateMessageRequest> requestBuilder,
            List<MessageParam> messages,
            RecoveryState state) throws FatalRecoveryException {

        while (true) {
            // ── 调 LLM,withRetry 处理 Path 3(429/529)─────────────────
            CreateMessageResponse response;
            try {
                response = withRetry(requestBuilder, state);
            } catch (AnthropicException e) {
                // ── Path 2: prompt_too_long → reactive compact + 重试一次 ──
                if (e.isPromptTooLong()
                        && !state.hasAttemptedReactiveCompact
                        && compactPipeline.hasReactiveSupport()) {
                    log.warn("[Recovery] prompt_too_long detected → reactive compact");
                    boolean ok = compactPipeline.reactiveCompact(messages);
                    if (!ok) {
                        log.error("[Recovery] reactive compact failed");
                        throw new FatalRecoveryException(
                                "Context too large and reactive compact failed: " + e.getMessage());
                    }
                    state.hasAttemptedReactiveCompact = true;
                    // messages 已被 reactiveCompact 原地修改 → 本 while 循环重新调用
                    continue;
                }
                // 已尝试过 reactive compact 仍然 prompt_too_long,或不可重试错误
                String reason = e.isPromptTooLong()
                        ? "Context still too large after reactive compact"
                        : type(e) + ": " + truncate(e.getMessage(), 200);
                log.error("[Recovery] unrecoverable: {}", reason);
                throw new FatalRecoveryException(reason);
            }

            // ── Path 1: stop_reason == max_tokens ───────────────────────
            if ("max_tokens".equals(response.getStopReason())) {
                // 第一次升级:不 append 截断输出,让 while 循环重试
                if (!state.hasEscalated) {
                    state.hasEscalated = true;
                    state.currentMaxTokens = cfg.getEscalatedMaxTokens();
                    log.info("[Recovery] max_tokens escalated {} → {}",
                            cfg.getDefaultMaxTokens(), cfg.getEscalatedMaxTokens());
                    continue;
                }
                // 已升级仍截断 → append 截断输出 + continuation prompt,while 循环续写
                if (state.recoveryCount < cfg.getMaxRecoveryRetries()) {
                    state.recoveryCount++;
                    log.info("[Recovery] continuation {}/{}",
                            state.recoveryCount, cfg.getMaxRecoveryRetries());
                    // s22 B2:messages mutation 从 caller (agentLoop) 搬来 recovery 内部,
                    // 保证 recovery 的 4 种旧结局对 caller 收敛成"成功 / fatal" 二元
                    messages.add(MessageParam.assistant(response.getContent()));
                    messages.add(MessageParam.user(cfg.getContinuationPrompt()));
                    continue;
                }
                log.error("[Recovery] max_tokens recovery limit reached");
                throw new FatalRecoveryException(
                        "Output truncated, max recovery retries reached");
            }

            // 成功拿到非 max_tokens 截断的 response
            return response;
        }
    }

    /**
     * Path 3 的核心:retry-with-backoff。封装单次 LLM 调用,把 429/529 转成
     * 指数退避重试。非重试错误(400/401/etc)直接抛,让外层 try/catch 处理 Path 2。
     *
     * <p>fallback 切换语义:连续 {@code maxConsecutive529} 次 529 后,**修改 state.currentModel**
     * 但**不重建 request**(那是调用方的事)。下一轮 retry 时 requestBuilder 看到新 model。
     */
    private CreateMessageResponse withRetry(
            Function<RecoveryState, CreateMessageRequest> requestBuilder,
            RecoveryState state) {

        AnthropicException lastError = null;
        for (int attempt = 0; attempt < cfg.getMaxRetries(); attempt++) {
            try {
                CreateMessageResponse r = client.createMessage(requestBuilder.apply(state));
                state.consecutive529 = 0;   // 一次成功清零
                return r;
            } catch (AnthropicException e) {
                lastError = e;
                int code = e.getStatusCode();

                // 429: rate limit → 指数退避重试
                if (code == 429) {
                    long delay = retryDelayMs(attempt);
                    log.warn("[Recovery] 429 rate limit, retry {}/{} after {} ms",
                            attempt + 1, cfg.getMaxRetries(), delay);
                    sleepQuietly(delay);
                    continue;
                }

                // 529 / 5xx: overloaded → 累加 + 退避;连续达阈值切 fallback
                if (code == 529 || (code >= 500 && code < 600)) {
                    state.consecutive529++;
                    if (state.consecutive529 >= cfg.getMaxConsecutive529()
                            && StringUtils.hasText(cfg.getFallbackModel())
                            && !state.currentModel.equals(cfg.getFallbackModel())) {
                        log.warn("[Recovery] 529 × {} → switching model {} → {}",
                                state.consecutive529, state.currentModel, cfg.getFallbackModel());
                        state.currentModel = cfg.getFallbackModel();
                        state.consecutive529 = 0;
                    }
                    long delay = retryDelayMs(attempt);
                    log.warn("[Recovery] {} overloaded, retry {}/{} after {} ms",
                            code, attempt + 1, cfg.getMaxRetries(), delay);
                    sleepQuietly(delay);
                    continue;
                }

                // 其它错误(prompt_too_long、400 invalid、401 auth、404 等)→ 抛给外层
                throw e;
            }
        }
        // 重试上限,把最后一次错误重新抛给外层(可能命中 prompt_too_long 路径)
        throw lastError != null ? lastError
                : new AnthropicException(0, "max retries exceeded with no error captured");
    }

    /**
     * 退避延迟计算:{@code min(base × 2^attempt, max) + 0~25% 抖动}。
     *
     * <p>抖动避免"惊群效应":多个客户端在同一时刻收到 529,如果都用确定的 backoff,
     * 重试还会撞在一起。25% 抖动让它们错开。
     */
    private long retryDelayMs(int attempt) {
        long base = Math.min((long) cfg.getBaseDelayMs() << attempt, cfg.getMaxDelayMs());
        long jitter = (long) (random.nextDouble() * base * 0.25);
        return base + jitter;
    }

    private void sleepQuietly(long ms) {
        try {
            sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Recovery sleep interrupted", ie);
        }
    }

    /** 测试可见:供单测覆盖 sleep 行为。 */
    void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private static String type(Throwable t) {
        return t.getClass().getSimpleName();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
