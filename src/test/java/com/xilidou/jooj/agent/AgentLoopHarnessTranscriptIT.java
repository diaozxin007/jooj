package com.xilidou.jooj.agent;

import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.cron.CronJob;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.session.Session;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * s22 P2 集成测试 —— 锁定 AgentLoopHarness 发出的事件真的能到 transcript。
 *
 * <p><b>命名约定</b>:类名后缀 {@code IT}(参考 {@link com.xilidou.jooj.mcp.SdkStdioMcpTransportIT}),
 * 表示这是 SpringBoot 上下文启动型集成测试,{@code mvn test} 默认不跑
 * (surefire 默认只包含 {@code *Test})。要跑本类需显式指定:
 * <pre>
 *   mvn -Dtest='AgentLoopHarnessTranscriptIT' test
 *   mvn -Dtest='*IT' test              # 跑所有 IT
 * </pre>
 *
 * <p>本类关注**跨模块的端到端流转**,不重复 TranscriptStoreTest / TranscriptServiceTest
 * 已经覆盖的语义。断言的是:
 *
 * <ul>
 *   <li>processOneQuery 走完一轮:transcript 有 user + assistant 两行,user content 干净</li>
 *   <li>processCronTriggers 走完:transcript 有 scheduled(role="scheduled")+ assistant</li>
 *   <li>D3:assistant 只记纯文本,即使 loop 里加了 nag/continuation 也不进 transcript</li>
 * </ul>
 *
 * <p>用 {@code @SpringBootTest} 加载整个容器,配合 {@link JoojTestConfig} 的 MockAnthropicClient
 * 替换真实 LLM。每个测试用独立 sessionId 避免互扰。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(JoojTestConfig.class)
class AgentLoopHarnessTranscriptIT {

    @Autowired AgentLoopHarness harness;
    @Autowired MockAnthropicClient mock;
    @Autowired TranscriptService transcriptService;
    @Autowired TranscriptStore transcriptStore;
    @Autowired SessionService sessionService;
    /** s22 架构审查(B1):cron 编排搬走 */
    @Autowired com.xilidou.jooj.cron.CronTurnOrchestrator cronOrchestrator;

    // 用不同 sid 隔离,避免跟 AgentLoopHarnessTest / 其他测试串味
    private static final String SID_USER = "s22p2-user";
    private static final String SID_CRON = "s22p2-cron";

    @BeforeEach
    void setUp() {
        // 确保 session 存在(processOneQuery 会 loadHistory 触发 lazy 创建,但先 create 显式点)
        if (!sessionService.exists(SID_USER)) {
            sessionService.createWithId(SID_USER, "P2 user IT");
        }
        if (!sessionService.exists(SID_CRON)) {
            sessionService.createWithId(SID_CRON, "P2 cron IT");
        }
        harness.clearHistory(SID_USER);
        harness.clearHistory(SID_CRON);
        // 清 transcript,让每次测试起点干净
        deleteTranscriptFiles(SID_USER);
        deleteTranscriptFiles(SID_CRON);
    }

    @AfterEach
    void tearDown() {
        mock.reset(req -> {
            throw new IllegalStateException("test forgot to call mock.reset(...)");
        });
        deleteTranscriptFiles(SID_USER);
        deleteTranscriptFiles(SID_CRON);
    }

    @Test
    @DisplayName("P2 processOneQuery: user 事件干净原文进 transcript,assistant 最终回复进 transcript")
    void process_one_query_publishes_user_and_assistant() throws IOException {
        mock.reset(req -> ResponseFixtures.endTurn("Hi there!"));

        harness.processOneQuery(SID_USER, "hello world");

        List<TranscriptLine> lines = transcriptService.readAll(SID_USER);
        assertEquals(2, lines.size(),
                "processOneQuery 应发 user + assistant 两个事件,transcript 有两行");

        TranscriptLine userLine = lines.get(0);
        assertEquals("user", userLine.role());
        assertEquals("hello world", userLine.content(),
                "干净原文 —— 不带任何 memory injection 前缀(即便 loop 内部 history 里带)");
        assertEquals("session", userLine.source(),
                "非 channel 入口 source 应为 \"session\"");

        TranscriptLine assistantLine = lines.get(1);
        assertEquals("assistant", assistantLine.role());
        assertEquals("Hi there!", assistantLine.content());
        assertNull(assistantLine.source(), "assistant source 应为 null");
    }

    @Test
    @DisplayName("P2 processCronTriggers (D7/D8): scheduled 事件 + assistant 事件都进 transcript")
    void process_cron_triggers_publishes_scheduled_and_assistant() throws IOException {
        mock.reset(req -> ResponseFixtures.endTurn("deploy is healthy"));

        CronJob job = new CronJob();
        job.setId("job-42");
        job.setSessionId(SID_CRON);
        job.setPrompt("check deploy");
        // 不设 deliveryType,兜底 "none",不走 channel 派发

        // s22 架构审查(B1):cron 编排搬到 CronTurnOrchestrator。
        cronOrchestrator.processFired(List.of(job));

        List<TranscriptLine> lines = transcriptService.readAll(SID_CRON);
        assertEquals(2, lines.size(),
                "cron 触发一轮:scheduled + assistant 两行");

        TranscriptLine scheduledLine = lines.get(0);
        assertEquals("scheduled", scheduledLine.role(),
                "D7:cron 不伪装 user role");
        assertEquals("check deploy", scheduledLine.content(),
                "干净原文 —— 不带 [Scheduled] 前缀(前缀只在 LLM 视图里)");
        assertEquals("cron:job-42", scheduledLine.source());

        TranscriptLine assistantLine = lines.get(1);
        assertEquals("assistant", assistantLine.role());
        assertEquals("deploy is healthy", assistantLine.content());
        assertNull(assistantLine.source(), "D8:cron 触发的 assistant 也 source=null");
    }

    @Test
    @DisplayName("P2 D3:multi-turn 场景下 transcript 累积 user + assistant 对")
    void process_multiple_turns_accumulates_pairs() throws IOException {
        mock.reset(req -> ResponseFixtures.endTurn("ok"));

        harness.processOneQuery(SID_USER, "q1");
        harness.processOneQuery(SID_USER, "q2");
        harness.processOneQuery(SID_USER, "q3");

        List<TranscriptLine> lines = transcriptService.readAll(SID_USER);
        assertEquals(6, lines.size(), "3 turn = 6 行(3 pairs)");
        assertEquals("q1", lines.get(0).content());
        assertEquals("ok", lines.get(1).content());
        assertEquals("q2", lines.get(2).content());
        assertEquals("ok", lines.get(3).content());
        assertEquals("q3", lines.get(4).content());
        assertEquals("ok", lines.get(5).content());
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
            // best-effort cleanup
        }
    }
}
