package com.xilidou.marvis.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.MarvisTestConfig;
import com.xilidou.marvis.http.MockAnthropicClient;
import com.xilidou.marvis.http.ResponseFixtures;
import com.xilidou.marvis.agent.AgentLoopHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 锁定 {@link ChatController} 的接口契约。
 *
 * <p>用 {@link MockMvc} 走全 HTTP 栈(包括 Jackson 序列化),
 * mock 掉 {@link com.xilidou.marvis.http.AnthropicClient}(由 {@link MarvisTestConfig} 提供)。
 *
 * <p><b>profile = test + web</b> —— 测试时启用 web profile 让 controller 被 Spring 扫到,
 * 同时 test profile 抑制 CLI runner 不阻塞 stdin。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MarvisTestConfig.class)
@ActiveProfiles({"test", "web"})
class ChatControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MockAnthropicClient mock;
    @Autowired AgentLoopHarness harness;
    @Autowired @Qualifier("agentLock") ReentrantLock agentLock;

    @BeforeEach
    void setUp() {
        harness.clearHistory();
        // 清残留 lock 状态(上一个测试可能没释放干净)
        while (agentLock.isHeldByCurrentThread()) agentLock.unlock();
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
                        .content(json.writeValueAsString(new ChatRequest("hi"))))
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
                        .content(json.writeValueAsString(new ChatRequest(""))))
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
    @DisplayName("POST /api/chat 当 lock 被占 → 409")
    void chat_lock_busy_returns_409() throws Exception {
        // ReentrantLock 是可重入的:同一线程持有时再 tryLock 仍成功。
        // 所以必须从另一个线程拿 lock,主线程(MockMvc 同步调 controller)才会 tryLock 失败。
        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            agentLock.lock();
            try {
                acquired.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            } finally {
                agentLock.unlock();
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();
        acquired.await(2, java.util.concurrent.TimeUnit.SECONDS);

        try {
            mvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new ChatRequest("hi"))))
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
                        .content(json.writeValueAsString(new ChatRequest("hi"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                // user 消息可能被 memory 丰富过(injection),只断言含 "hi"
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
                        .content(json.writeValueAsString(new ChatRequest("hi"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historySize").value(0));

        mvc.perform(get("/api/history"))
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
                        .content(json.writeValueAsString(new ChatRequest("read x.txt"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("read it"))
                .andExpect(jsonPath("$.toolCalls").isArray())
                .andExpect(jsonPath("$.toolCalls[0]").value("read_file"));
    }
}
