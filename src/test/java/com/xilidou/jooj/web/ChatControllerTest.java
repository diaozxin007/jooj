package com.xilidou.jooj.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.compact.CompactConfig;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.agent.InterruptRegistry;
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
    @Autowired InterruptRegistry interruptRegistry;

    @BeforeEach
    void setUp() {
        harness.clearHistory(SID);
        // 清残留 lock 状态(上一个测试可能没释放干净)
        ReentrantLock lock = lockProvider.lockFor(SID);
        while (lock.isHeldByCurrentThread()) lock.unlock();
        interruptRegistry.clear(SID);
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
                interruptRegistry.isRequested(SID));

        mvc.perform(post("/api/chat/" + SID + "/interrupt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(true))
                .andExpect(jsonPath("$.sessionId").value(SID));

        org.junit.jupiter.api.Assertions.assertTrue(
                interruptRegistry.isRequested(SID),
                "endpoint 应把 sid 登记进 registry");
    }

    @Test
    @DisplayName("POST /api/chat/{sid}/interrupt 重复请求返回 requested=false(幂等)")
    void interrupt_duplicate_request_returns_false() throws Exception {
        interruptRegistry.request(SID);  // 提前登记

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
}
