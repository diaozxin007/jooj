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
 * <p>s22 架构审查(2026-07-13, B2 refactor):{@code call} 现在**只暴露成功 / 失败二元结局**。
 * 原来的 4-branch RecoveryResult sealed type 被删除,内部循环消化 EscalateAndRetry /
 * AppendContinuation。所以测试断言从"返回哪种 result" 变成"返回 response(什么样)/ 抛
 * FatalRecoveryException(消息含什么)"。
 *
 * <p>还有一个连带变化:原来 EscalateAndRetry 让 caller 重试(mock 只需 1 次调用就返回结果),
 * 现在 recovery 内部循环 → 每个 test 需要提供**多个 mock response** 让内部循环有得跑。
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
        recoveryCfg.setContinuationPrompt("Please continue.");

        props = new JoojProperties();
        props.setRecovery(recoveryCfg);
        totalSleepMs = 0;
    }

    /**
     * 自定义 Coordinator:覆写 sleep 不真睡,只累加。CompactPipeline 用空实现,
     * 由 hasReactiveSupport 控制是否声称支持。
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

    /** 帮助:构造一个用固定 model + state 的 request builder。 */
    private Function<RecoveryState, CreateMessageRequest> buildSimpleRequest() {
        return state -> CreateMessageRequest.builder()
                .model(state.getCurrentModel())
                .maxTokens(state.getCurrentMaxTokens())
                .messages(List.of(MessageParam.user("hi")))
                .build();
    }

    // ── Path 0: 正常路径 ─────────────────────────────────────────

    @Test
    @DisplayName("一次成功 → 直接返 response")
    void happy_path_returns_response() throws Exception {
        var mock = MockAnthropicClient.ofResponses(ResponseFixtures.endTurn("ok"));
        var coordinator = newCoordinator(mock, false);
        var state = new RecoveryState("m1", 8000);

        CreateMessageResponse r = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertNotNull(r);
        assertEquals("end_turn", r.getStopReason());
        assertEquals(1, mock.getCallCount());
        assertEquals(0, state.getConsecutive529());
    }

    // ── Path 3: 429 退避 ─────────────────────────────────────────

    @Test
    @DisplayName("429 一次然后成功 → 内部重试后返 response")
    void retries_on_429_then_succeeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var mock = new MockAnthropicClient(req -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new AnthropicException(429, "rate limit exceeded");
            return ResponseFixtures.endTurn("ok");
        });
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        CreateMessageResponse r = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertNotNull(r);
        assertEquals(2, mock.getCallCount(), "429 + 重试 = 2 次调用");
        assertTrue(totalSleepMs > 0, "至少 sleep 一次");
    }

    @Test
    @DisplayName("持续 429 超过 maxRetries → 抛 FatalRecoveryException")
    void exhausts_retries_on_persistent_429() {
        var mock = MockAnthropicClient.throwing(new AnthropicException(429, "always rate limited"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        FatalRecoveryException ex = assertThrows(FatalRecoveryException.class,
                () -> coordinator.call(buildSimpleRequest(), new ArrayList<>(), state));

        assertTrue(ex.getReason().contains("429") || ex.getReason().contains("rate"),
                "Fatal 原因应含 429/rate 相关关键字: " + ex.getReason());
        assertEquals(recoveryCfg.getMaxRetries(), mock.getCallCount(),
                "应该重试 maxRetries 次后放弃");
    }

    // ── Path 3: 529 fallback 切换 ──────────────────────────────

    @Test
    @DisplayName("连续 maxConsecutive529 次 529 → 切 fallback model 后成功")
    void switches_to_fallback_after_consecutive_529() throws Exception {
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
        CreateMessageResponse r = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertNotNull(r);
        assertEquals("fallback-model", state.getCurrentModel(),
                "连续 3 次 529 后应切到 fallback");
        assertEquals(0, state.getConsecutive529(), "切 fallback 后清零");
        assertEquals("fallback-model", mock.getLastRequest().getModel());
    }

    @Test
    @DisplayName("没配 fallback 时,529 持续重试到 maxRetries → 抛 Fatal")
    void no_fallback_keeps_retrying_on_529() {
        recoveryCfg.setMaxRetries(4);

        var mock = MockAnthropicClient.throwing(new AnthropicException(529, "overloaded"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        FatalRecoveryException ex = assertThrows(FatalRecoveryException.class,
                () -> coordinator.call(buildSimpleRequest(), new ArrayList<>(), state));

        assertNotNull(ex.getReason());
        assertEquals(4, mock.getCallCount());
        assertEquals("m1", state.getCurrentModel(), "fallback 没配置应保持原 model");
    }

    // ── Path 1: max_tokens 升级(现在内部消化)──────────────────

    @Test
    @DisplayName("首次 max_tokens 截断 → 内部升级 currentMaxTokens 后再试,直到成功返 response")
    void max_tokens_first_truncation_escalates_internally() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var mock = new MockAnthropicClient(req -> {
            int n = calls.incrementAndGet();
            if (n == 1) return ResponseFixtures.maxTokensTruncated("partial");
            return ResponseFixtures.endTurn("full response");
        });
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        CreateMessageResponse r = coordinator.call(buildSimpleRequest(), new ArrayList<>(), state);

        assertNotNull(r);
        assertEquals("end_turn", r.getStopReason(),
                "内部 escalate + retry 后应返回正常 end_turn");
        assertTrue(state.isHasEscalated());
        assertEquals(64000, state.getCurrentMaxTokens(),
                "escalate 后 currentMaxTokens 应升级");
        assertEquals(0, state.getRecoveryCount(),
                "首次 escalate 不计 continuation 次数");
        assertEquals(2, mock.getCallCount(), "1 次截断 + 1 次成功 = 2 调用");
    }

    @Test
    @DisplayName("已升级仍截断 → 内部 append truncated + continuation prompt,重试后成功")
    void max_tokens_after_escalation_appends_continuation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var mock = new MockAnthropicClient(req -> {
            int n = calls.incrementAndGet();
            if (n == 1) return ResponseFixtures.maxTokensTruncated("still cut");
            return ResponseFixtures.endTurn("continuation done");
        });
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        // 模拟已 escalate 过一次(caller-side setup)
        state.hasEscalated = true;
        state.currentMaxTokens = 64000;

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("original query"));

        CreateMessageResponse r = coordinator.call(buildSimpleRequest(), messages, state);

        assertNotNull(r);
        assertEquals("end_turn", r.getStopReason());
        assertEquals(1, state.getRecoveryCount(),
                "触发一次 continuation");
        // Recovery 内部 append 2 条:截断的 assistant + continuation prompt user
        assertEquals(3, messages.size(),
                "original + truncated assistant + continuation user = 3 条");
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("user", messages.get(2).getRole());
    }

    @Test
    @DisplayName("持续 max_tokens 截断超过 maxRecoveryRetries → 抛 Fatal")
    void max_tokens_recovery_limit_throws_fatal() {
        var mock = MockAnthropicClient.ofResponses(
                ResponseFixtures.maxTokensTruncated("forever cut"),
                ResponseFixtures.maxTokensTruncated("still cut 1"),
                ResponseFixtures.maxTokensTruncated("still cut 2"),
                ResponseFixtures.maxTokensTruncated("final cut"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        FatalRecoveryException ex = assertThrows(FatalRecoveryException.class,
                () -> coordinator.call(buildSimpleRequest(), new ArrayList<>(), state));

        String reason = ex.getReason().toLowerCase();
        assertTrue(reason.contains("truncated") || reason.contains("max"),
                "Fatal 原因应提到 truncated/max: " + ex.getReason());
    }

    // ── Path 2: prompt_too_long ────────────────────────────────

    @Test
    @DisplayName("prompt_too_long + reactive 支持 → 内部触发 compact + 重试后成功")
    void prompt_too_long_triggers_reactive_compact() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var mock = new MockAnthropicClient(req -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new AnthropicException(
                    400,
                    "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long\"}}");
            return ResponseFixtures.endTurn("recovered");
        });
        var coordinator = newCoordinator(mock, true);  // hasReactiveSupport

        var state = new RecoveryState("m1", 8000);
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("first"));
        messages.add(MessageParam.user("second"));
        messages.add(MessageParam.user("third"));

        CreateMessageResponse r = coordinator.call(buildSimpleRequest(), messages, state);

        assertNotNull(r);
        assertEquals("end_turn", r.getStopReason());
        assertTrue(state.isHasAttemptedReactiveCompact());
        assertEquals(1, messages.size(), "reactiveCompact 把 messages 砍到 1 条");
        assertEquals(2, mock.getCallCount());
    }

    @Test
    @DisplayName("prompt_too_long 第二次(已 attempted)→ Fatal")
    void prompt_too_long_second_time_is_fatal() {
        var mock = MockAnthropicClient.throwing(
                new AnthropicException(400, "{\"error\":{\"message\":\"prompt is too long\"}}"));
        var coordinator = newCoordinator(mock, true);

        var state = new RecoveryState("m1", 8000);
        state.hasAttemptedReactiveCompact = true;

        FatalRecoveryException ex = assertThrows(FatalRecoveryException.class,
                () -> coordinator.call(buildSimpleRequest(), new ArrayList<>(), state));

        assertTrue(ex.getReason().toLowerCase().contains("context"),
                "Fatal 应提到 context: " + ex.getReason());
    }

    @Test
    @DisplayName("prompt_too_long 但 compactPipeline 没有 reactive 支持 → Fatal")
    void prompt_too_long_no_reactive_support_is_fatal() {
        var mock = MockAnthropicClient.throwing(
                new AnthropicException(400, "{\"error\":{\"message\":\"prompt is too long\"}}"));
        var coordinator = newCoordinator(mock, false);  // 不支持

        var state = new RecoveryState("m1", 8000);
        assertThrows(FatalRecoveryException.class,
                () -> coordinator.call(buildSimpleRequest(), new ArrayList<>(), state));
    }

    // ── 不可重试错误 ─────────────────────────────────────────────

    @Test
    @DisplayName("400 invalid_request 直接 Fatal,不重试")
    void non_retryable_4xx_is_fatal_immediately() {
        var mock = MockAnthropicClient.throwing(
                new AnthropicException(400, "invalid request"));
        var coordinator = newCoordinator(mock, false);

        var state = new RecoveryState("m1", 8000);
        FatalRecoveryException ex = assertThrows(FatalRecoveryException.class,
                () -> coordinator.call(buildSimpleRequest(), new ArrayList<>(), state));

        assertNotNull(ex.getReason());
        assertEquals(1, mock.getCallCount(), "不该重试");
    }
}
