package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.control.AllowAnswer;
import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.AskTimeoutException;
import com.xilidou.jooj.agent.control.ChoiceAnswer;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.DenyAnswer;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.channel.InboundDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TuiQueryDispatcher 单元测试(s23 P5)。
 *
 * <p>只测**入队 / pending answer 路由**逻辑。worker 线程本身不启动 —— 单元测试关注 offer /
 * tryAnswerPending 的路由决策,worker 的 dispatchSync 集成走 IT 层。
 */
@DisplayName("TuiQueryDispatcher 路由 (s23 P5)")
class TuiQueryDispatcherTest {

    private static final String SID = com.xilidou.jooj.session.Session.CLI_DEFAULT_ID;

    private InboundDispatcher dispatcher;
    private AgentControl agentControl;
    private TuiQueryDispatcher qd;

    @BeforeEach
    void setUp() {
        dispatcher = Mockito.mock(InboundDispatcher.class);
        agentControl = new FakeAgentControl();
        TuiProperties props = new TuiProperties();
        props.setQueueCapacity(3);          // 小 cap 便于测满
        qd = new TuiQueryDispatcher(dispatcher, agentControl, props);
        // 不调 start() —— worker 线程不启动,queue.take 也就不消费,offer 逻辑独立测
    }

    // ─────────────────────────────────────────────────────────────
    //  Queue offer / capacity
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("空队列 offer 成功,size 递增")
    void offer_empty_success() {
        assertThat(qd.queueSize()).isZero();
        assertThat(qd.offer("q1")).isTrue();
        assertThat(qd.queueSize()).isOne();
        assertThat(qd.offer("q2")).isTrue();
        assertThat(qd.queueSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("队列满 offer 返 false")
    void offer_full_rejects() {
        assertThat(qd.offer("q1")).isTrue();
        assertThat(qd.offer("q2")).isTrue();
        assertThat(qd.offer("q3")).isTrue();
        assertThat(qd.queueSize()).isEqualTo(3);
        // 满了
        assertThat(qd.offer("q4")).isFalse();
        assertThat(qd.queueSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("isIdle:队列 + inFlight 都空才 idle")
    void isIdle() {
        assertThat(qd.isIdle()).isTrue();
        qd.offer("q1");
        assertThat(qd.isIdle()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    //  tryAnswerPending: Permission
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("无 pending → NO_PENDING")
    void answer_no_pending() {
        assertThat(qd.tryAnswerPending("a")).isEqualTo(TuiQueryDispatcher.AnswerResult.NO_PENDING);
    }

    @Test
    @DisplayName("Permission + 'a' → ANSWERED (Allow)")
    void answer_permission_allow() {
        PermissionQuestion pq = new PermissionQuestion(
                "askid-1", Instant.now(), "bash", "{cmd:ls}", "test-reason", "tui", "local");
        ((FakeAgentControl) agentControl).addPending(SID, pq);

        assertThat(qd.tryAnswerPending("a")).isEqualTo(TuiQueryDispatcher.AnswerResult.ANSWERED);
        Answer captured = ((FakeAgentControl) agentControl).lastAnswer;
        assertThat(captured).isInstanceOf(AllowAnswer.class);
    }

    @Test
    @DisplayName("Permission + 'allow' / 'yes' / '允许' 都 ANSWERED Allow")
    void answer_permission_allow_variants() {
        for (String txt : new String[]{"allow", "y", "yes", "允许", "同意"}) {
            PermissionQuestion pq = new PermissionQuestion(
                    "askid-" + txt, Instant.now(), "bash", "{}", "r", "tui", "local");
            ((FakeAgentControl) agentControl).clearPending();
            ((FakeAgentControl) agentControl).addPending(SID, pq);
            assertThat(qd.tryAnswerPending(txt)).isEqualTo(TuiQueryDispatcher.AnswerResult.ANSWERED);
            assertThat(((FakeAgentControl) agentControl).lastAnswer).isInstanceOf(AllowAnswer.class);
        }
    }

    @Test
    @DisplayName("Permission + 'd' → ANSWERED Deny")
    void answer_permission_deny() {
        PermissionQuestion pq = new PermissionQuestion(
                "askid-2", Instant.now(), "bash", "{}", "r", "tui", "local");
        ((FakeAgentControl) agentControl).addPending(SID, pq);

        assertThat(qd.tryAnswerPending("d")).isEqualTo(TuiQueryDispatcher.AnswerResult.ANSWERED);
        assertThat(((FakeAgentControl) agentControl).lastAnswer).isInstanceOf(DenyAnswer.class);
    }

    @Test
    @DisplayName("Permission + 无关文本 → PARSE_FAILED")
    void answer_permission_gibberish_fails() {
        PermissionQuestion pq = new PermissionQuestion(
                "askid-3", Instant.now(), "bash", "{}", "r", "tui", "local");
        ((FakeAgentControl) agentControl).addPending(SID, pq);

        assertThat(qd.tryAnswerPending("what is the weather"))
                .isEqualTo(TuiQueryDispatcher.AnswerResult.PARSE_FAILED);
    }

    // ─────────────────────────────────────────────────────────────
    //  tryAnswerPending: Clarify
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Clarify 单题 'A' → ANSWERED ChoiceAnswer")
    void answer_clarify_single() {
        ClarifyQuestion.SubQuestion sub = new ClarifyQuestion.SubQuestion(
                "选哪个?", "选择",
                List.of(new ClarifyQuestion.Option("Java", null),
                        new ClarifyQuestion.Option("Python", null)),
                false);
        ClarifyQuestion cq = new ClarifyQuestion("askid-c1", Instant.now(),
                List.of(sub), "tui", "local");
        ((FakeAgentControl) agentControl).addPending(SID, cq);

        assertThat(qd.tryAnswerPending("A")).isEqualTo(TuiQueryDispatcher.AnswerResult.ANSWERED);
        Answer a = ((FakeAgentControl) agentControl).lastAnswer;
        assertThat(a).isInstanceOf(ChoiceAnswer.class);
        assertThat(((ChoiceAnswer) a).selections()).containsEntry("0", List.of("Java"));
    }

    @Test
    @DisplayName("Clarify 无法 parse → PARSE_FAILED")
    void answer_clarify_gibberish_fails() {
        ClarifyQuestion.SubQuestion sub = new ClarifyQuestion.SubQuestion(
                "选哪个?", "sel",
                List.of(new ClarifyQuestion.Option("Java", null),
                        new ClarifyQuestion.Option("Python", null)),
                false);
        ClarifyQuestion cq = new ClarifyQuestion("askid-c2", Instant.now(),
                List.of(sub), "tui", "local");
        ((FakeAgentControl) agentControl).addPending(SID, cq);

        assertThat(qd.tryAnswerPending("random gibberish text"))
                .isEqualTo(TuiQueryDispatcher.AnswerResult.PARSE_FAILED);
    }

    // ─────────────────────────────────────────────────────────────
    //  denyAllPending
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("denyAllPending 全部 deny,返回 denied 数")
    void deny_all_pending() {
        PermissionQuestion pq1 = new PermissionQuestion(
                "askid-1", Instant.now(), "bash", "{}", "r", "tui", "local");
        PermissionQuestion pq2 = new PermissionQuestion(
                "askid-2", Instant.now(), "bash", "{}", "r", "tui", "local");
        ((FakeAgentControl) agentControl).addPending(SID, pq1);
        ((FakeAgentControl) agentControl).addPending(SID, pq2);

        int denied = qd.denyAllPending("test-reason");
        assertThat(denied).isEqualTo(2);
    }

    @Test
    @DisplayName("空 pending 时 denyAllPending 返 0")
    void deny_empty() {
        assertThat(qd.denyAllPending("x")).isZero();
    }

    // ─────────────────────────────────────────────────────────────
    //  简易 fake AgentControl(不用 Mockito,因接口方法多;需要真的存 pending)
    // ─────────────────────────────────────────────────────────────

    private static class FakeAgentControl implements AgentControl {
        private final java.util.List<PendingQuestion> pending = new java.util.ArrayList<>();
        Answer lastAnswer;

        void addPending(String sid, PendingQuestion q) { pending.add(q); }
        void clearPending() { pending.clear(); lastAnswer = null; }

        @Override public boolean requestInterrupt(String sessionId) { return true; }
        @Override public boolean consumeInterrupt(String sessionId) { return false; }
        @Override public boolean isInterruptRequested(String sessionId) { return false; }
        @Override public void clearInterrupt(String sessionId) {}
        @Override public Answer ask(String sessionId, PendingQuestion question, Duration timeout)
                throws AskTimeoutException {
            throw new AskTimeoutException("not implemented in fake");
        }
        @Override public java.util.List<PendingQuestion> listPending(String sessionId) {
            return java.util.List.copyOf(pending);
        }
        @Override public boolean answer(String sessionId, String askId, Answer answer) {
            for (java.util.Iterator<PendingQuestion> it = pending.iterator(); it.hasNext(); ) {
                if (it.next().askId().equals(askId)) {
                    it.remove();
                    lastAnswer = answer;
                    return true;
                }
            }
            return false;
        }
        @Override public java.util.Optional<PendingQuestion> findPending(String sessionId, String askId) {
            return pending.stream().filter(p -> p.askId().equals(askId)).findFirst();
        }
        @Override public int cancelPending(String sessionId) {
            int n = pending.size();
            pending.clear();
            return n;
        }
    }
}
