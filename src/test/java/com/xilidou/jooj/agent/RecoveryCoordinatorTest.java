package com.xilidou.jooj.agent;

import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.compact.CompactPipeline;
import com.xilidou.jooj.http.AnthropicException;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.MessageParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecoveryCoordinator 三条恢复路径的核心行为锁定。
 *
 * <p>不走 SpringBootTest:Coordinator 是纯逻辑组件(没有 @Autowired 链路),
 * 直接 new 即可,测试启动 &lt; 100ms。
 *
 * <p>s22 架构审查(2026-07-13):RecoveryCoordinator 现在在**构造器**里持有
 * AnthropicClient,不再在 {@code call(...)} 里传。每个测试通过 {@link #newCoordinator}
 * 传入自己的 mock。cfg 在 {@link #setup()} 里初始化,某些用例修改 cfg 后需要重新
 * {@code newCoordinator} 让新 cfg 生效。
 */
class RecoveryCoordinatorTest {

    /** 测试专用配置:激进默认,让 backoff 测试 < 100ms 完成。 */
    private JoojProperties.Recovery recoveryCfg;
    /** props 存 recoveryCfg,newCoordinator 从 props 取 cfg + defaultModel。 */
    private JoojProperties props;
    /** 真正的 sleep 累计时长(测试断言用)。 */
    private long totalSleepMs;

    @BeforeEach
    void setup() {
        recoveryCfg = new JoojProperties.Recovery();
        recoveryCfg.setMaxRetries(5);
        recoveryCfg.setBaseDelayMs(10);
        recoveryCfg.setMaxDelayMs(40);
        recoveryCfg.setMaxConsecutive529(3);
        recoveryCfg.setFallbackModel("");
        recoveryCfg.setDefaultMaxTokens(8000);
        recoveryCfg.setEscalatedMaxTokens(64000);
        recoveryCfg.setMaxRecoveryRetries(2);

        props = new JoojProperties();
        props.setRecovery(recoveryCfg);
        totalSleepMs = 0;
    }

    /**
     * 自定义 Coordinator:覆写 sleep 不真睡,只累加。CompactPipeline 用空实现,
     * 由 hasReactiveSupport 控制是否声称支持。
     *
     * <p>s22 架构审查:RecoveryCoordinator 构造器新增 AnthropicClient 参数,
     * 每个测试用自己的 mock。
     */
    private RecoveryCoordinator newCoordinator(MockAnthropicClient mock, boolean hasReactiveSupport) {
        CompactPipeline fakeCompact = new CompactPipeline() {
            @Override public boolean hasReactiveSupport() { return hasReactiveSupport; }
            @Override public boolean reactiveCompact(List<MessageParam> messages) {
                if (messages.size() > 1) {
                    MessageParam first = messages.get(0);
                    messages.clear();
                    messages.add(first);
                }
                return true;
            }
        };
        return new RecoveryCoordinator(props, fakeCompact, mock) {
            @Override
            void sleep(long ms) {
                totalSleepMs += ms;
            }
        };
    }

    /** 帮助:构造一个永远返回固定 request 的 builder(忽略 state)。 */
    private Function<RecoveryState, CreateMessageRequest> buildSimpleRequest() {
        return state -> CreateMessageRequest.builder()
                .model(state.getCurrentModel())
                .maxTokens(state.getCurrentMaxTokens())
                .messages(List.of(MessageParam.user("hi")))
                .build();
    }

    // ── Path 0: 正常路径 ─────────────────────────────────────────

    @Test
    @DisplayName("一次成功 → Done(response)")
    void happy_path_returns_done() {
        var mock = MockAnthropicClient.ofResponses(ResponseFixtures.endTurn("ok"));
        var coordinator = newCoordinator(mock, false);
        var state = new RecoveryState("m1", 8000);

        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Done.class, result);
        assertEquals(1, mock.getCallCount());
        assertEquals(0, state.getConsecutive529());
    }

    // ── Path 3: 429 退避 ─────────────────────────────────────────

    @Test
    @DisplayName("429 一次然后成功 → 重试一次后 Done")
    void retries_on_429_then_succeeds() {
        AtomicInteger calls = new AtomicInteger();
        var mock = new MockAnthropicClient(req -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new AnthropicException(429, "rate limit exceeded");
            return ResponseFixtures.endTurn("ok");
        });
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Done.class, result);
        assertEquals(2, mock.getCallCount(), "429 + 重试 = 2 次调用");
        assertTrue(totalSleepMs > 0, "至少 sleep 一次");
    }

    @Test
    @DisplayName("超过 maxRetries 次 429 → 抛 AnthropicException → Fatal")
    void exhausts_retries_on_persistent_429() {
        var mock = MockAnthropicClient.throwing(new AnthropicException(429, "always rate limited"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Fatal.class, result);
        assertEquals(recoveryCfg.getMaxRetries(), mock.getCallCount(),
                "应该重试 maxRetries 次后放弃");
    }

    // ── Path 3: 529 fallback 切换 ──────────────────────────────

    @Test
    @DisplayName("连续 maxConsecutive529 次 529 → 切 fallback model 后成功")
    void switches_to_fallback_after_consecutive_529() {
        recoveryCfg.setFallbackModel("fallback-model");
        recoveryCfg.setMaxRetries(10);

        AtomicInteger calls = new AtomicInteger();
        var mock = new MockAnthropicClient(req -> {
            int n = calls.incrementAndGet();
            if (n < 4) throw new AnthropicException(529, "overloaded");
            return ResponseFixtures.endTurn("ok");
        });
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("primary-model", 8000);
        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Done.class, result);
        assertEquals("fallback-model", state.getCurrentModel(),
                "连续 3 次 529 后应切到 fallback");
        assertEquals(0, state.getConsecutive529(), "切 fallback 后清零");

        var lastReq = mock.getLastRequest();
        assertEquals("fallback-model", lastReq.getModel());
    }

    @Test
    @DisplayName("没配 fallback 时,529 持续重试到 maxRetries")
    void no_fallback_keeps_retrying_on_529() {
        recoveryCfg.setMaxRetries(4);

        var mock = MockAnthropicClient.throwing(new AnthropicException(529, "overloaded"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Fatal.class, result);
        assertEquals(4, mock.getCallCount());
        assertEquals("m1", state.getCurrentModel(), "fallback 没配置应保持原 model");
    }

    // ── Path 1: max_tokens 升级 ────────────────────────────────

    @Test
    @DisplayName("第一次 max_tokens 截断 → EscalateAndRetry(不 append)+ 升级 currentMaxTokens")
    void max_tokens_first_truncation_escalates() {
        var mock = MockAnthropicClient.ofResponses(ResponseFixtures.maxTokensTruncated("partial"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.EscalateAndRetry.class, result);
        assertTrue(state.isHasEscalated());
        assertEquals(64000, state.getCurrentMaxTokens(),
                "currentMaxTokens 应升级到 escalatedMaxTokens");
        assertEquals(0, state.getRecoveryCount(),
                "EscalateAndRetry 不计 continuation 次数");
    }

    @Test
    @DisplayName("已升级再次截断 → AppendContinuation,recoveryCount 累加")
    void max_tokens_after_escalation_appends_continuation() {
        var mock = MockAnthropicClient.ofResponses(ResponseFixtures.maxTokensTruncated("still cut"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        state.hasEscalated = true;
        state.currentMaxTokens = 64000;

        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.AppendContinuation.class, result);
        assertEquals(1, state.getRecoveryCount());
    }

    @Test
    @DisplayName("超过 maxRecoveryRetries 次 continuation → Fatal")
    void max_tokens_recovery_limit_returns_fatal() {
        var mock = MockAnthropicClient.ofResponses(ResponseFixtures.maxTokensTruncated("forever cut"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        state.hasEscalated = true;
        state.currentMaxTokens = 64000;
        state.recoveryCount = 2;

        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Fatal.class, result);
        assertTrue(((RecoveryResult.Fatal) result).reason().toLowerCase().contains("max"),
                "Fatal 原因应该提到 max(_tokens 或 recovery)");
    }

    // ── Path 2: prompt_too_long ────────────────────────────────

    @Test
    @DisplayName("prompt_too_long + reactive 支持 → 触发 reactiveCompact + EscalateAndRetry")
    void prompt_too_long_triggers_reactive_compact() {
        var mock = MockAnthropicClient.throwing(
                new AnthropicException(400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long\"}}"));
        var coordinator = newCoordinator(mock, true);  // hasReactiveSupport

        var state = new RecoveryState("m1", 8000);
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("first"));
        messages.add(MessageParam.user("second"));
        messages.add(MessageParam.user("third"));

        var result = coordinator.call(buildSimpleRequest(), messages, state);

        assertInstanceOf(RecoveryResult.EscalateAndRetry.class, result);
        assertTrue(state.isHasAttemptedReactiveCompact());
        assertEquals(1, messages.size(), "reactiveCompact 把 messages 砍到 1 条");
    }

    @Test
    @DisplayName("prompt_too_long 第二次(已 attempted)→ Fatal")
    void prompt_too_long_second_time_is_fatal() {
        var mock = MockAnthropicClient.throwing(
                new AnthropicException(400, "{\"error\":{\"message\":\"prompt is too long\"}}"));
        var coordinator = newCoordinator(mock, true);

        var state = new RecoveryState("m1", 8000);
        state.hasAttemptedReactiveCompact = true;

        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Fatal.class, result);
    }

    @Test
    @DisplayName("prompt_too_long 但 compactPipeline 没有 reactive 支持 → Fatal")
    void prompt_too_long_no_reactive_support_is_fatal() {
        var mock = MockAnthropicClient.throwing(
                new AnthropicException(400, "{\"error\":{\"message\":\"prompt is too long\"}}"));
        var coordinator = newCoordinator(mock, false);  // 不支持

        var state = new RecoveryState("m1", 8000);

        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Fatal.class, result);
    }

    // ── 不可重试错误 ─────────────────────────────────────────────

    @Test
    @DisplayName("400 invalid_request 直接 Fatal,不重试")
    void non_retryable_4xx_is_fatal_immediately() {
        var mock = MockAnthropicClient.throwing(
                new AnthropicException(400, "invalid request"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        var result = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertInstanceOf(RecoveryResult.Fatal.class, result);
        assertEquals(1, mock.getCallCount(), "不该重试");
    }
}
