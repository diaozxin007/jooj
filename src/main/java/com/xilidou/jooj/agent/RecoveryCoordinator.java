package com.xilidou.jooj.agent;

import com.xilidou.jooj.compact.CompactPipeline;
import com.xilidou.jooj.http.AnthropicProperties;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.llm.LlmClient;
import com.xilidou.jooj.llm.adapter.AnthropicShapeBridge;
import com.xilidou.jooj.llm.domain.LlmErrorKind;
import com.xilidou.jooj.llm.domain.LlmException;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmStopReason;
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
 * <h3>三条恢复路径</h3>
 * <ul>
 *   <li><b>Path 1</b>(MAX_TOKENS 截断):第一次升级 max_tokens(default → escalated,
 *       **不 append** 截断输出);仍截断时 append + 注入 continuation prompt,
 *       最多 {@code maxRecoveryRetries} 次</li>
 *   <li><b>Path 2</b>(PROMPT_TOO_LONG):触发 {@link CompactPipeline#reactiveCompact}
 *       (LLM 摘要历史)+ 重试一次;不行抛 fatal</li>
 *   <li><b>Path 3</b>(RATE_LIMITED / OVERLOADED):{@link #withRetry} 用指数退避 +
 *       抖动重试 {@code maxRetries} 次;连续 {@code maxConsecutive529} 次 OVERLOADED
 *       后切到 {@code fallbackModel}</li>
 * </ul>
 *
 * <h3>P2 迁移(2026-07-14):vendor-neutral</h3>
 *
 * <p>本类现已完全 vendor-neutral:
 * <ul>
 *   <li>持有 {@link LlmClient} —— 通过 {@link com.xilidou.jooj.http.ModelRouter} 路由到
 *       Anthropic / OpenAI / DeepSeek 任一 provider</li>
 *   <li>错误分派用 {@link LlmErrorKind} 枚举,不再依赖 Anthropic 错误消息字符串匹配</li>
 *   <li>stop_reason 用 {@link LlmStopReason} 枚举,不再是 String</li>
 * </ul>
 *
 * <p>messages 参数仍是 {@code List<MessageParam>} —— Step G 会连同 SessionStore
 * 序列化格式一并迁到 {@code List<LlmMessage>}。Path 1 continuation 里用
 * {@link AnthropicShapeBridge} 把 canonical 响应桥接回 MessageParam,过渡期用。
 */
@Component
@Slf4j
public class RecoveryCoordinator {

    private final LlmClient client;
    private final String defaultModel;
    private final RecoveryProperties cfg;
    private final CompactPipeline compactPipeline;
    private final Random random = new Random();

    public RecoveryCoordinator(AnthropicProperties anthropic, RecoveryProperties recoveryProps,
                               CompactPipeline compactPipeline, LlmClient client) {
        this.client = client;
        this.defaultModel = anthropic.getModel();
        this.cfg = recoveryProps;
        this.compactPipeline = compactPipeline;

        if (StringUtils.hasText(cfg.getFallbackModel())) {
            log.info("[Recovery] fallback model configured: {}", cfg.getFallbackModel());
        }
    }

    /** 创建 per-turn RecoveryState —— 用默认 model 和 max_tokens 启动。 */
    public RecoveryState newState() {
        return new RecoveryState(defaultModel, cfg.getDefaultMaxTokens());
    }

    /**
     * 调用 LLM + 处理三条恢复路径,内部循环直到拿到可用 response 或 fatal。
     *
     * @param requestBuilder  接 RecoveryState 返回 canonical {@link LlmRequest}
     * @param messages        对话历史(**Path 1 continuation / Path 2 compact 会原地修改**)
     * @param state           per-agent-loop 状态机(由 {@link #newState()} 创建)
     * @return 成功的 LLM response(保证 stop_reason != MAX_TOKENS)
     * @throws FatalRecoveryException 三条路径都尝试过后仍无法恢复
     */
    public LlmResponse call(
            Function<RecoveryState, LlmRequest> requestBuilder,
            List<MessageParam> messages,
            RecoveryState state) throws FatalRecoveryException {

        while (true) {
            LlmResponse response;
            try {
                response = withRetry(requestBuilder, state);
            } catch (LlmException e) {
                // Path 2: PROMPT_TOO_LONG → reactive compact + 重试一次
                if (e.getKind() == LlmErrorKind.PROMPT_TOO_LONG
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
                    continue;
                }
                String reason = e.getKind() == LlmErrorKind.PROMPT_TOO_LONG
                        ? "Context still too large after reactive compact"
                        : e.getKind() + ": " + truncate(e.getMessage(), 200);
                log.error("[Recovery] unrecoverable: {}", reason);
                throw new FatalRecoveryException(reason);
            }

            // Path 1: stop_reason == MAX_TOKENS
            if (response.getStopReason() == LlmStopReason.MAX_TOKENS) {
                if (!state.hasEscalated) {
                    state.hasEscalated = true;
                    state.currentMaxTokens = cfg.getEscalatedMaxTokens();
                    log.info("[Recovery] max_tokens escalated {} → {}",
                            cfg.getDefaultMaxTokens(), cfg.getEscalatedMaxTokens());
                    continue;
                }
                if (state.recoveryCount < cfg.getMaxRecoveryRetries()) {
                    state.recoveryCount++;
                    log.info("[Recovery] continuation {}/{}",
                            state.recoveryCount, cfg.getMaxRecoveryRetries());
                    // 桥接 canonical LlmResponse.content → wire ContentBlock list,
                    // 塞进 MessageParam.assistant(...);Step G 迁完 SessionStore 后可删。
                    messages.add(MessageParam.assistant(
                            AnthropicShapeBridge.contentToWire(response.getContent())));
                    messages.add(MessageParam.user(cfg.getContinuationPrompt()));
                    continue;
                }
                log.error("[Recovery] max_tokens recovery limit reached");
                throw new FatalRecoveryException(
                        "Output truncated, max recovery retries reached");
            }

            // 成功:usage 推给 CompactPipeline
            if (response.getUsage() != null) {
                compactPipeline.updateFromResponse(bridgeUsage(response.getUsage()));
            }
            return response;
        }
    }

    /** 桥接 canonical LlmUsage → wire Usage,供 CompactPipeline 消费(Step D 迁完可删)。 */
    private static com.xilidou.jooj.http.dto.Usage bridgeUsage(com.xilidou.jooj.llm.domain.LlmUsage u) {
        return new com.xilidou.jooj.http.dto.Usage(
                u.getInputTokens(),
                u.getOutputTokens(),
                u.getCacheCreationInputTokens(),
                u.getCacheReadInputTokens());
    }

    /**
     * Path 3:retry-with-backoff。RATE_LIMITED / OVERLOADED / IO_ERROR 走重试;
     * 其它 kind 直接抛给外层(可能命中 Path 2 的 PROMPT_TOO_LONG 分支)。
     */
    private LlmResponse withRetry(
            Function<RecoveryState, LlmRequest> requestBuilder,
            RecoveryState state) {

        LlmException lastError = null;
        for (int attempt = 0; attempt < cfg.getMaxRetries(); attempt++) {
            try {
                LlmResponse r = client.createMessage(requestBuilder.apply(state));
                state.consecutive529 = 0;
                return r;
            } catch (LlmException e) {
                lastError = e;

                if (e.getKind() == LlmErrorKind.RATE_LIMITED) {
                    long delay = retryDelayMs(attempt);
                    log.warn("[Recovery] rate limited, retry {}/{} after {} ms",
                            attempt + 1, cfg.getMaxRetries(), delay);
                    sleepQuietly(delay);
                    continue;
                }

                if (e.getKind() == LlmErrorKind.OVERLOADED
                        || e.getKind() == LlmErrorKind.IO_ERROR) {
                    state.consecutive529++;
                    if (state.consecutive529 >= cfg.getMaxConsecutive529()
                            && StringUtils.hasText(cfg.getFallbackModel())
                            && !state.currentModel.equals(cfg.getFallbackModel())) {
                        log.warn("[Recovery] overloaded × {} → switching model {} → {}",
                                state.consecutive529, state.currentModel, cfg.getFallbackModel());
                        state.currentModel = cfg.getFallbackModel();
                        state.consecutive529 = 0;
                    }
                    long delay = retryDelayMs(attempt);
                    log.warn("[Recovery] {} overloaded, retry {}/{} after {} ms",
                            e.getKind(), attempt + 1, cfg.getMaxRetries(), delay);
                    sleepQuietly(delay);
                    continue;
                }

                // PROMPT_TOO_LONG / BAD_REQUEST / AUTH → 抛给外层
                throw e;
            }
        }
        throw lastError != null ? lastError
                : new LlmException(LlmErrorKind.UNKNOWN, 0,
                        "max retries exceeded with no error captured");
    }

    /** 退避延迟:min(base × 2^attempt, max) + 0~25% 抖动。 */
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
