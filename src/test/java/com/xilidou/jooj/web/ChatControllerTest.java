package com.xilidou.jooj.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.compact.CompactConfig;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.agent.control.AllowAnswer;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 锁定 {@link ChatController} 的接口契约。
 *
 * <p>用 {@link MockMvc} 走全 HTTP 栈(包括 Jackson 序列化),
 * mock 掉 {@link com.xilidou.jooj.http.AnthropicClient}(由 {@link JoojTestConfig} 提供)。
 *
 * <p><b>profile = test + web</b> —— 测试时启用 web profile 让 controller 被 Spring 扫到,
 * 同时 test profile 抑制 CLI runner 不阻塞 stdin。
 *
 * <h3>Session 抽象 patch 后</h3>
 *
 * <p>每个 chat / history / clear 请求都需要带 sessionId(空白时退化到 default)。
 * 测试统一用 {@code default} session 简化,锁也走 default 那把。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(JoojTestConfig.class)
@ActiveProfiles({"test", "web"})
class ChatControllerTest {

    private static final String SID = Session.DEFAULT_ID;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MockAnthropicClient mock;
    @Autowired AgentLoopHarness harness;
    @Autowired AgentLockProvider lockProvider;
    @Autowired CompactConfig compactConfig;
    @Autowired AgentControl agentControl;
    @Autowired com.xilidou.jooj.agent.TurnEventStream turnEventStream;

    @BeforeEach
    void setUp() {
        harness.clearHistory(SID);
        // 清残留 lock 状态(上一个测试可能没释放干净)
        ReentrantLock lock = lockProvider.lockFor(SID);
        while (lock.isHeldByCurrentThread()) lock.unlock();
        agentControl.clearInterrupt(SID);
    }

    @AfterEach
    void tearDown() {
        mock.reset(req -> {
            throw new IllegalStateException("test forgot to call mock.reset(...)");
        });
    }

    @Test
    @DisplayName("POST /api/chat 正常路径返 reply + historySize + toolCalls")
    void chat_happy_path() throws Exception {
        mock.reset(ResponseFixtures.endTurn("Hello back"));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChatRequest(SID, "hi"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Hello back"))
                .andExpect(jsonPath("$.historySize").value(2))    // user + assistant
                .andExpect(jsonPath("$.toolCalls").isArray())
                .andExpect(jsonPath("$.toolCalls.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/chat 空 query → 400")
    void chat_empty_query_returns_400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChatRequest(SID, ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/chat null body → 400")
    void chat_null_body_returns_400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/chat 未知 sessionId → 400")
    void chat_unknown_session_returns_400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new ChatRequest("non-existent-session-id-xyz", "hi"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/chat 当 default session lock 被占 → 409")
    void chat_lock_busy_returns_409() throws Exception {
        // ReentrantLock 是可重入的:同一线程持有时再 tryLock 仍成功。
        // 所以必须从另一个线程拿 lock,主线程(MockMvc 同步调 controller)才会 tryLock 失败。
        ReentrantLock targetLock = lockProvider.lockFor(SID);
        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            targetLock.lock();
            try {
                acquired.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            } finally {
                targetLock.unlock();
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();
        acquired.await(2, java.util.concurrent.TimeUnit.SECONDS);

        try {
            mvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new ChatRequest(SID, "hi"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").exists());
        } finally {
            release.countDown();
            holder.join(2000);
        }
    }

    @Test
    @DisplayName("GET /api/history 返完整历史(role + 揉平 text)")
    void history_returns_flattened_messages() throws Exception {
        mock.reset(ResponseFixtures.endTurn("hello"));

        // 先跑一轮聊天
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChatRequest(SID, "hi"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/history?sessionId=" + SID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].text").exists())
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].text").value("hello"));
    }

    @Test
    @DisplayName("POST /api/clear 清空历史")
    void clear_resets_history() throws Exception {
        mock.reset(ResponseFixtures.endTurn("ok"));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChatRequest(SID, "hi"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/clear?sessionId=" + SID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historySize").value(0));

        mvc.perform(get("/api/history?sessionId=" + SID))
                .andExpect(jsonPath("$.messages.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/chat 工具调用后 toolCalls 列表反映本轮调用")
    void chat_with_tool_use_returns_tool_calls() throws Exception {
        mock.reset(
                ResponseFixtures.toolUse("read_file", Map.of("path", "x.txt"), "tu_001"),
                ResponseFixtures.endTurn("read it")
        );

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChatRequest(SID, "read x.txt"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("read it"))
                .andExpect(jsonPath("$.toolCalls").isArray())
                .andExpect(jsonPath("$.toolCalls[0]").value("read_file"));
    }

    // ─────────────────────────────────────────────────────────────
    //  /api/snip-archive —— 展开 SnipCompactor 归档的中段对话
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/snip-archive should read jsonl archive and return flattened entries")
    void snipArchive_should_read_jsonl_and_flatten() throws Exception {
        // 构造一个位于 transcriptDir 内的归档文件
        Path dir = compactConfig.transcriptDir();
        Files.createDirectories(dir);
        Path archive = Files.createTempFile(dir, "snip-test-", ".jsonl");
        try {
            Files.writeString(archive,
                    "{\"role\":\"user\",\"content\":\"你好\"}\n" +
                            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"回复\"}]}\n");

            mvc.perform(get("/api/snip-archive").param("path", archive.toAbsolutePath().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messages[0].role").value("user"))
                    .andExpect(jsonPath("$.messages[0].text").value("你好"))
                    .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                    .andExpect(jsonPath("$.messages[1].text").value("回复"));
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    @Test
    @DisplayName("GET /api/snip-archive should reject path traversal outside transcriptDir")
    void snipArchive_should_reject_path_outside_transcript_dir() throws Exception {
        // 试图读 /etc/passwd —— 不在 transcriptDir 内
        mvc.perform(get("/api/snip-archive").param("path", "/etc/passwd"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("outside")));
    }

    @Test
    @DisplayName("GET /api/snip-archive should 404 when archive file missing")
    void snipArchive_should_404_when_missing() throws Exception {
        Path dir = compactConfig.transcriptDir();
        Files.createDirectories(dir);
        Path missing = dir.resolve("snip-does-not-exist.jsonl");
        mvc.perform(get("/api/snip-archive").param("path", missing.toAbsolutePath().toString()))
                .andExpect(status().isNotFound());
    }

    // ── s22 D-8:interrupt endpoint 测试 ─────────────────────────

    @Test
    @DisplayName("POST /api/chat/{sid}/interrupt 首次请求返回 requested=true,登记到 registry")
    void interrupt_first_request_returns_true() throws Exception {
        org.junit.jupiter.api.Assertions.assertFalse(
                agentControl.isInterruptRequested(SID));

        mvc.perform(post("/api/chat/" + SID + "/interrupt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(true))
                .andExpect(jsonPath("$.sessionId").value(SID));

        org.junit.jupiter.api.Assertions.assertTrue(
                agentControl.isInterruptRequested(SID),
                "endpoint 应把 sid 登记进 registry");
    }

    @Test
    @DisplayName("POST /api/chat/{sid}/interrupt 重复请求返回 requested=false(幂等)")
    void interrupt_duplicate_request_returns_false() throws Exception {
        agentControl.requestInterrupt(SID);  // 提前登记

        mvc.perform(post("/api/chat/" + SID + "/interrupt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(false))
                .andExpect(jsonPath("$.sessionId").value(SID));
    }

    @Test
    @DisplayName("POST /api/chat//interrupt(空 sid)应返回 404(路径不匹配)")
    void interrupt_missing_sid_returns_404() throws Exception {
        // Spring path variable 不允许空段 → 路径解析失败,由框架返 404
        mvc.perform(post("/api/chat//interrupt"))
                .andExpect(status().is4xxClientError());
    }

    // ── s22 D-10-B:pending / answer 端点测试 ─────────────────

    @Test
    @DisplayName("GET /api/chat/{sid}/pending:无挂起时返 pending=[]")
    void pending_empty_when_no_ask() throws Exception {
        mvc.perform(get("/api/chat/" + SID + "/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SID))
                .andExpect(jsonPath("$.pending").isArray())
                .andExpect(jsonPath("$.pending.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/chat/{sid}/pending:PermissionQuestion 挂起时返完整字段")
    void pending_returns_permission_question_fields() throws Exception {
        // 手动插一个 pending —— DefaultAgentControl 的 ask 会 block,这里用 answer 直接短路
        // 更干净:用 CompletableFuture 起 async 挂起 + 检查 /pending
        var q = new PermissionQuestion(
                "askid-test-1",
                java.time.Instant.parse("2026-07-13T12:00:00Z"),
                "bash",
                "{cmd: rm -rf}",
                "matched destructive pattern");

        // 起个 async 挂起
        var agentThread = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                agentControl.ask(SID, q, java.time.Duration.ofSeconds(5));
            } catch (Exception ignore) {
                // cancel/timeout/answer 都会走到这里,测试 not care
            }
        });

        // 等 ask 真正挂起(polling pending 数量,防止时序 flake)
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && !agentControl.findPending(SID, "askid-test-1").isPresent()) {
            Thread.sleep(10);
        }

        try {
            mvc.perform(get("/api/chat/" + SID + "/pending"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pending.length()").value(1))
                    .andExpect(jsonPath("$.pending[0].askId").value("askid-test-1"))
                    .andExpect(jsonPath("$.pending[0].type").value("permission"))
                    .andExpect(jsonPath("$.pending[0].toolName").value("bash"))
                    .andExpect(jsonPath("$.pending[0].toolInput").value("{cmd: rm -rf}"))
                    .andExpect(jsonPath("$.pending[0].reason").value("matched destructive pattern"));
        } finally {
            agentControl.cancelPending(SID);
            agentThread.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("POST /api/chat/{sid}/answer:decision=allow 唤醒 agent")
    void answer_allow_wakes_agent() throws Exception {
        var q = new PermissionQuestion("askid-allow-1",
                java.time.Instant.now(), "bash", "{}", "test");

        var received = new java.util.concurrent.atomic.AtomicReference<
                com.xilidou.jooj.agent.control.Answer>();
        var agentThread = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                var a = agentControl.ask(SID, q, java.time.Duration.ofSeconds(5));
                received.set(a);
            } catch (Exception ignore) {}
        });

        // 等挂起
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && agentControl.findPending(SID, "askid-allow-1").isEmpty()) {
            Thread.sleep(10);
        }

        mvc.perform(post("/api/chat/" + SID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"askId\":\"askid-allow-1\",\"decision\":\"allow\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answered").value(true))
                .andExpect(jsonPath("$.askId").value("askid-allow-1"));

        agentThread.get(2, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertTrue(
                received.get() instanceof AllowAnswer,
                "agent 线程应该收到 AllowAnswer,实际:" + received.get());
    }

    @Test
    @DisplayName("POST /api/chat/{sid}/answer:decision=deny + reason 唤醒 agent 走 DENY 路径")
    void answer_deny_with_reason() throws Exception {
        var q = new PermissionQuestion("askid-deny-1",
                java.time.Instant.now(), "bash", "{}", "test");

        var received = new java.util.concurrent.atomic.AtomicReference<
                com.xilidou.jooj.agent.control.Answer>();
        var agentThread = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                received.set(agentControl.ask(SID, q, java.time.Duration.ofSeconds(5)));
            } catch (Exception ignore) {}
        });

        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && agentControl.findPending(SID, "askid-deny-1").isEmpty()) {
            Thread.sleep(10);
        }

        mvc.perform(post("/api/chat/" + SID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"askId\":\"askid-deny-1\",\"decision\":\"deny\",\"reason\":\"用户不允许\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answered").value(true));

        agentThread.get(2, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertTrue(received.get().isDeny());
        org.junit.jupiter.api.Assertions.assertEquals("用户不允许",
                ((com.xilidou.jooj.agent.control.DenyAnswer) received.get()).reason());
    }

    @Test
    @DisplayName("POST /api/chat/{sid}/answer:askId 不存在返 404")
    void answer_missing_askid_returns_404() throws Exception {
        mvc.perform(post("/api/chat/" + SID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"askId\":\"nonexistent\",\"decision\":\"allow\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/chat/{sid}/answer:参数缺失 / decision 非法 → 400")
    void answer_invalid_params_returns_400() throws Exception {
        // askId 缺
        mvc.perform(post("/api/chat/" + SID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"allow\"}"))
                .andExpect(status().isBadRequest());

        // decision 缺
        mvc.perform(post("/api/chat/" + SID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"askId\":\"x\"}"))
                .andExpect(status().isBadRequest());

        // decision 非法值
        mvc.perform(post("/api/chat/" + SID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"askId\":\"x\",\"decision\":\"maybe\"}"))
                .andExpect(status().isNotFound());  // askId=x 找不到 pending,先返 404
    }

    // ── s22 D-11:events 端点测试 ─────────────────────────────

    @Test
    @DisplayName("GET /api/chat/{sid}/events:无事件时返 events=[],latestSeq=0")
    void events_empty_when_no_pushes() throws Exception {
        turnEventStream.clear(SID);
        mvc.perform(get("/api/chat/" + SID + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SID))
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events.length()").value(0))
                .andExpect(jsonPath("$.latestSeq").value(0));
    }

    @Test
    @DisplayName("GET /api/chat/{sid}/events:push 后返完整字段")
    void events_returns_pushed_summaries() throws Exception {
        turnEventStream.clear(SID);
        turnEventStream.push(SID, com.xilidou.jooj.agent.TurnEvent.toolStart("$ mvn test"));
        turnEventStream.push(SID, com.xilidou.jooj.agent.TurnEvent.toolStart("📖 pom.xml"));

        mvc.perform(get("/api/chat/" + SID + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].seq").value(1))
                .andExpect(jsonPath("$.events[0].type").value("tool_start"))
                .andExpect(jsonPath("$.events[0].summary").value("$ mvn test"))
                .andExpect(jsonPath("$.events[1].seq").value(2))
                .andExpect(jsonPath("$.events[1].summary").value("📖 pom.xml"))
                .andExpect(jsonPath("$.latestSeq").value(2));

        turnEventStream.clear(SID);
    }

    @Test
    @DisplayName("GET /api/chat/{sid}/events?since=N:增量返 seq > N")
    void events_since_returns_delta_only() throws Exception {
        turnEventStream.clear(SID);
        turnEventStream.push(SID, com.xilidou.jooj.agent.TurnEvent.toolStart("a"));
        turnEventStream.push(SID, com.xilidou.jooj.agent.TurnEvent.toolStart("b"));
        turnEventStream.push(SID, com.xilidou.jooj.agent.TurnEvent.toolStart("c"));

        mvc.perform(get("/api/chat/" + SID + "/events?since=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].seq").value(3))
                .andExpect(jsonPath("$.events[0].summary").value("c"))
                .andExpect(jsonPath("$.latestSeq").value(3));

        turnEventStream.clear(SID);
    }

    @Test
    @DisplayName("GET /api/chat//events(空 sid)返 4xx")
    void events_missing_sid_returns_4xx() throws Exception {
        mvc.perform(get("/api/chat//events"))
                .andExpect(status().is4xxClientError());
    }

    // ── s22 AQ:ClarifyQuestion 场景 ─────────────────────────

    @Test
    @DisplayName("GET /pending:ClarifyQuestion 挂起 → 返 type=clarify + questions[] 完整字段")
    void pending_returns_clarify_question_fields() throws Exception {
        turnEventStream.clear(SID);
        agentControl.clearInterrupt(SID);

        var q = com.xilidou.jooj.agent.control.ClarifyQuestion.of(java.util.List.of(
                new com.xilidou.jooj.agent.control.ClarifyQuestion.SubQuestion(
                        "用哪个 UI 库?", "UI lib",
                        java.util.List.of(
                                new com.xilidou.jooj.agent.control.ClarifyQuestion.Option("React", "生态最大"),
                                new com.xilidou.jooj.agent.control.ClarifyQuestion.Option("Vue", null)),
                        false)));

        var agentThread = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                agentControl.ask(SID, q, java.time.Duration.ofSeconds(5));
            } catch (Exception ignore) {
            }
        });

        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && agentControl.findPending(SID, q.askId()).isEmpty()) {
            Thread.sleep(10);
        }

        try {
            mvc.perform(get("/api/chat/" + SID + "/pending"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pending.length()").value(1))
                    .andExpect(jsonPath("$.pending[0].type").value("clarify"))
                    .andExpect(jsonPath("$.pending[0].questions.length()").value(1))
                    .andExpect(jsonPath("$.pending[0].questions[0].question").value("用哪个 UI 库?"))
                    .andExpect(jsonPath("$.pending[0].questions[0].header").value("UI lib"))
                    .andExpect(jsonPath("$.pending[0].questions[0].multiSelect").value(false))
                    .andExpect(jsonPath("$.pending[0].questions[0].options.length()").value(2))
                    .andExpect(jsonPath("$.pending[0].questions[0].options[0].label").value("React"))
                    .andExpect(jsonPath("$.pending[0].questions[0].options[0].description").value("生态最大"))
                    .andExpect(jsonPath("$.pending[0].questions[0].options[1].label").value("Vue"))
                    // Vue 的 description 是 null,应 absent(NON_NULL 序列化)
                    .andExpect(jsonPath("$.pending[0].questions[0].options[1].description").doesNotExist());
        } finally {
            agentControl.cancelPending(SID);
            agentThread.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("POST /answer:decision=choice + selections → agent 收到 ChoiceAnswer")
    void answer_choice_wakes_agent() throws Exception {
        turnEventStream.clear(SID);
        agentControl.clearInterrupt(SID);

        var q = com.xilidou.jooj.agent.control.ClarifyQuestion.of(java.util.List.of(
                new com.xilidou.jooj.agent.control.ClarifyQuestion.SubQuestion(
                        "UI?", "ui",
                        java.util.List.of(
                                new com.xilidou.jooj.agent.control.ClarifyQuestion.Option("React", null),
                                new com.xilidou.jooj.agent.control.ClarifyQuestion.Option("Vue", null)),
                        false)));

        var received = new java.util.concurrent.atomic.AtomicReference<
                com.xilidou.jooj.agent.control.Answer>();
        var agentThread = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                received.set(agentControl.ask(SID, q, java.time.Duration.ofSeconds(5)));
            } catch (Exception ignore) {
            }
        });
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && agentControl.findPending(SID, q.askId()).isEmpty()) {
            Thread.sleep(10);
        }

        String body = "{\"askId\":\"" + q.askId() + "\",\"decision\":\"choice\","
                + "\"selections\":{\"0\":[\"React\"]}}";
        mvc.perform(post("/api/chat/" + SID + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answered").value(true));

        agentThread.get(2, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertTrue(
                received.get() instanceof com.xilidou.jooj.agent.control.ChoiceAnswer,
                "应收到 ChoiceAnswer,实际:" + received.get());
        var ca = (com.xilidou.jooj.agent.control.ChoiceAnswer) received.get();
        org.junit.jupiter.api.Assertions.assertEquals("React", ca.firstSingle());
    }

    @Test
    @DisplayName("POST /answer:decision=choice 缺 selections 返 400")
    void answer_choice_missing_selections() throws Exception {
        turnEventStream.clear(SID);
        agentControl.clearInterrupt(SID);

        var q = com.xilidou.jooj.agent.control.ClarifyQuestion.of(java.util.List.of(
                new com.xilidou.jooj.agent.control.ClarifyQuestion.SubQuestion(
                        "?", "h",
                        java.util.List.of(
                                new com.xilidou.jooj.agent.control.ClarifyQuestion.Option("A", null),
                                new com.xilidou.jooj.agent.control.ClarifyQuestion.Option("B", null)),
                        false)));

        var agentThread = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { agentControl.ask(SID, q, java.time.Duration.ofSeconds(5)); } catch (Exception ignore) {}
        });
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline
                && agentControl.findPending(SID, q.askId()).isEmpty()) {
            Thread.sleep(10);
        }

        try {
            mvc.perform(post("/api/chat/" + SID + "/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"askId\":\"" + q.askId() + "\",\"decision\":\"choice\"}"))
                    .andExpect(status().isBadRequest());
        } finally {
            agentControl.cancelPending(SID);
            agentThread.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
