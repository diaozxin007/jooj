package com.xilidou.jooj.agent;

import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.transcript.TranscriptLine;
import com.xilidou.jooj.transcript.TranscriptService;
import com.xilidou.jooj.transcript.TranscriptStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 D-8 集成测试 —— 验证用户 interrupt 请求能真正打断 agentLoop,
 * 并且 messages + transcript 都反映了打断状态。
 *
 * <h3>覆盖 3 个场景</h3>
 *
 * <ol>
 *   <li>while 顶部检查点:第 2 轮 turn 开始前 interrupt → 抛异常后 append [Interrupted by user] +
 *       publish TurnInterrupted 事件</li>
 *   <li>tool 之间检查点:第一个 tool 跑完 → interrupt → 第二个 tool 不执行,turn 结束</li>
 *   <li>无 interrupt 的 baseline:多 turn 正常跑,不会误触发中断路径</li>
 * </ol>
 *
 * <p>本类是 {@code IT},{@code mvn test} 默认不跑;跑法:
 * <pre>
 *   mvn -Dtest='InterruptIT' test
 *   mvn -Dtest='*IT' test
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(JoojTestConfig.class)
class InterruptIT {

    @Autowired AgentLoopHarness harness;
    @Autowired MockAnthropicClient mock;
    @Autowired TranscriptService transcriptService;
    @Autowired TranscriptStore transcriptStore;
    @Autowired SessionService sessionService;
    @Autowired AgentControl agentControl;

    private static final String SID = "s22d8-interrupt-it";

    @BeforeEach
    void setUp() {
        if (!sessionService.exists(SID)) {
            sessionService.createWithId(SID, "D-8 interrupt IT");
        }
        harness.clearHistory(SID);
        agentControl.clearInterrupt(SID);
        deleteTranscriptFiles(SID);
    }

    @AfterEach
    void tearDown() {
        mock.reset(req -> {
            throw new IllegalStateException("test forgot to call mock.reset(...)");
        });
        agentControl.clearInterrupt(SID);
        deleteTranscriptFiles(SID);
    }

    @Test
    @DisplayName("while 顶部检查点:第一轮 turn 之后 request interrupt,第二轮 turn 开头被打断")
    void interrupt_at_while_top_between_turns() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        mock.reset(req -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                // 第一轮 LLM 让 loop 调一次 bash 工具(会跑完并返回 tool_result)
                return ResponseFixtures.toolUse("bash", Map.of("command", "echo hi"), "tu1");
            }
            // 应该跑不到这里 —— 第 2 轮开始前会被打断
            fail("第 2 轮 turn 不该发起 LLM 请求,应已被 interrupt 打断");
            return ResponseFixtures.endTurn("should not reach");
        });

        // 提前 request interrupt —— agentLoop 会在第一次 while 迭代完 tool call 后
        // 回到 while 顶部检查时消费掉,不发起第 2 轮 LLM 请求
        agentControl.requestInterrupt(SID);
        assertTrue(agentControl.isInterruptRequested(SID));

        harness.processOneQuery(SID, "please do a thing");

        // consume 应发生,pending 应清空
        assertFalse(agentControl.isInterruptRequested(SID), "interrupt flag 应被消费清除");

        // messages 里应有 [Interrupted by user] user 消息(打断前状态 + 打断标记)
        List<LlmMessage> history = sessionService.loadHistory(SID);
        boolean hasInterruptedMarker = history.stream()
                .filter(m -> m.getRole() == LlmRole.USER)
                .anyMatch(m -> m.getContent() != null
                        && m.getContent().stream()
                                .filter(c -> c instanceof LlmText)
                                .map(c -> ((LlmText) c).getText())
                                .anyMatch(s -> s != null && s.contains("[Interrupted by user]")));
        assertTrue(hasInterruptedMarker,
                "history 应包含 [Interrupted by user] user 消息,history=" + history);

        // transcript 应有 role="interrupted" 一行
        List<TranscriptLine> lines = transcriptService.readAll(SID);
        boolean hasInterruptedLine = lines.stream()
                .anyMatch(l -> "interrupted".equals(l.role()));
        assertTrue(hasInterruptedLine,
                "transcript 应有 role=interrupted 一行,实际:" + lines);
    }

    @Test
    @DisplayName("baseline:无 interrupt 请求时多轮 turn 正常完成,不误触发中断路径")
    void no_interrupt_normal_flow() throws IOException {
        mock.reset(req -> ResponseFixtures.endTurn("all good"));

        harness.processOneQuery(SID, "hi");

        List<TranscriptLine> lines = transcriptService.readAll(SID);
        assertEquals(2, lines.size(), "user + assistant 两行");
        assertEquals("user", lines.get(0).role());
        assertEquals("assistant", lines.get(1).role(),
                "无 interrupt 时应发 AssistantResponseCompleted 而非 TurnInterrupted");
        assertEquals("all good", lines.get(1).content());
        assertFalse(agentControl.isInterruptRequested(SID), "无 interrupt 请求,pending 应空");
    }

    // ── 私有工具 ─────────────────────────────────────────────────

    private void deleteTranscriptFiles(String sid) {
        try {
            Path main = transcriptStore.transcriptsDir().resolve(sid + ".jsonl");
            Files.deleteIfExists(main);
            Path deleted = transcriptStore.deletedDir();
            if (Files.exists(deleted)) {
                try (var stream = Files.list(deleted)) {
                    stream.filter(p -> p.getFileName().toString().startsWith(sid + "-"))
                          .forEach(p -> {
                              try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                          });
                }
            }
        } catch (IOException ignore) {
        }
    }
}
