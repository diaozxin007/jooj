package com.xilidou.jooj.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojTestConfig;
import com.xilidou.jooj.http.MockAnthropicClient;
import com.xilidou.jooj.http.ResponseFixtures;
import com.xilidou.jooj.session.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3.2 staged write_approval 端到端集成测试(s21 Demo 27 review)。
 *
 * <p>**走真容器** —— SpringBootTest 全 wire,Mock 只换 {@link com.xilidou.jooj.http.AnthropicClient}
 * (避免发真 HTTP,但所有 Spring 装配 / Bean 注入 / 异步 executor / hook 链 / slash 命令路由
 * 都是真的)。
 *
 * <p>**覆盖完整路径**:
 * <ol>
 *   <li>jooj 跑一次真对话(Mock LLM 返 fact + reviewer 返 staged proposal)</li>
 *   <li>异步 BackgroundReviewer 在 BgExecutor 跑完,提案落到 PendingMemoryStore</li>
 *   <li>Web POST /api/chat 走 /memory pending 看到提案</li>
 *   <li>Web POST /api/chat 走 /memory approve 1 promote 到 MemoryStore</li>
 *   <li>磁盘 fs 验证:.pending.json 空了 + memoryDir 有 approved entry</li>
 * </ol>
 *
 * <p>**为什么走 Web 入口而不是 InboundDispatcher**:Web 用 MockMvc 不需要起 weixin SDK / 网络,
 * 测试快且稳;InboundDispatcher 的 slash 路由跟 ChatController 共享 SlashCommandRegistry,
 * 验证 ChatController 路径等价于验证全套 slash 路由。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(JoojTestConfig.class)
@ActiveProfiles({"test", "web"})
class WriteApprovalE2EIntegrationTest {

    private static Path memoryTmpDir;

    /**
     * 启用 staged 模式 + 隔离 memory 目录。
     * {@code @DynamicPropertySource} 优先级高于 yml,在 ApplicationContext 初始化前生效。
     */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry reg) throws Exception {
        memoryTmpDir = Files.createTempDirectory("jooj-p3-e2e-");
        reg.add("jooj.memory.write-approval", () -> "true");
        reg.add("jooj.memory.memory-dir", memoryTmpDir::toString);
        // 别让 Consolidator 干扰(默认 10 文件触发,这里不让它跑)
        reg.add("jooj.memory.consolidate-threshold", () -> "9999");
    }

    @Autowired MockMvc mvc;
    @Autowired MockAnthropicClient mock;
    @Autowired MemoryStore memoryStore;
    @Autowired PendingMemoryStore pendingStore;
    @Autowired MemoryService memoryService;
    @Autowired ObjectMapper json;

    private static final String SID = Session.DEFAULT_ID;

    @BeforeEach
    void setUp() throws Exception {
        // 每个测试清掉 pending pool / memory store 的状态(共享容器,需手动清)
        pendingStore.clear();
        for (var m : memoryStore.list()) {
            try {
                Files.deleteIfExists(memoryTmpDir.resolve(m.getFilename()));
            } catch (Exception ignored) {
            }
        }
        // 索引也重建
        Files.deleteIfExists(memoryTmpDir.resolve("MEMORY.md"));
    }

    @Test
    @DisplayName("E2E:turn → reviewer 异步 propose → /memory pending → /memory approve → 落 store")
    void full_staged_write_approval_flow() throws Exception {
        // ① 准备 Mock LLM:用 dispatcher pattern 按 SYSTEM prompt 内容决定返哪个 fixture
        // 这样每次主对话 / Extractor / Reviewer 调用都会被正确路由,不会因 fixture 用完 500
        String reviewerJson =
                "[{\"name\":\"feedback-prefer-rg\"," +
                        "\"description\":\"User prefers ripgrep over grep\"," +
                        "\"body\":\"Always use rg for searches.\"}]";

        com.xilidou.jooj.http.dto.CreateMessageResponse mainTurn = ResponseFixtures.endTurn("ok, switching.");
        com.xilidou.jooj.http.dto.CreateMessageResponse extractorEmpty = ResponseFixtures.endTurn("[]");
        com.xilidou.jooj.http.dto.CreateMessageResponse reviewerProposal = ResponseFixtures.endTurn(reviewerJson);

        mock.reset(req -> {
            // SYSTEM 含 "self-improvement reviewer" → 是 Reviewer;含 "memory extractor" → 是 Extractor
            // CreateMessageRequest 提供 getSystemText() 把 system 转成纯文本(无论 String 还是 List<SystemTextBlock>)
            String sys = req.getSystemText();
            if (sys.contains("self-improvement reviewer")) return reviewerProposal;
            if (sys.contains("memory extractor")) return extractorEmpty;
            return mainTurn;
        });

        // ② 用户跟 jooj 说一段够长的对话(reviewer 要求 messages.size() >= 4)
        // 先从干净状态开始:用 /clear 清除可能累积的历史
        mvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "sessionId", SID,
                        "query", "/clear"))))
                .andExpect(status().isOk());

        // 实际对话:为了让 messages >= 4,我们用 3 条 user message 顺次
        for (String q : new String[] {
                "use grep to find imports",
                "no, use ripgrep — grep is too slow",
                "now find TODOs — same, use ripgrep not grep"
        }) {
            mvc.perform(post("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of(
                            "sessionId", SID, "query", q))))
                    .andExpect(status().isOk());
        }

        // ③ 等异步 BackgroundReviewer 在 BgExecutor 完成 propose
        // 用 polling wait,2 秒上限够 mock LLM 调用 + propose 写盘
        boolean reviewerDone = pollUntil(
                () -> pendingStore.count() >= 1,
                2000, 50);
        assertTrue(reviewerDone,
                "Reviewer 异步应在 2s 内 propose 一条 (实际 count=" + pendingStore.count() + ")");

        // ④ 验证 pending pool 真有提案(每 turn 都触发 review,3 turn 共 3 条;
        // 但 mock 只返同一份提案,id 单调递增,所以第一条仍是 id=1 / name=feedback-prefer-rg)
        var pending = pendingStore.readAll();
        assertTrue(pending.size() >= 1, "Reviewer 至少应 propose 1 条,实际:" + pending.size());
        var first = pending.get(0);
        assertEquals("feedback-prefer-rg", first.getMemory().getName());
        assertEquals("reviewer", first.getSource());
        long pendingId = first.getId();

        // ⑤ 走 Web /memory pending:LLM 不应被调(slash 命令短路);response 含提案
        MvcResult listResult = mvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "sessionId", SID, "query", "/memory pending"))))
                .andExpect(status().isOk())
                .andReturn();
        String listReply = listResult.getResponse().getContentAsString();
        assertTrue(listReply.contains("feedback-prefer-rg"),
                "Web /memory pending 应列出提案,实际:" + listReply);
        assertTrue(listReply.contains("#" + pendingId));

        // ⑥ 走 Web /memory approve <id>:promote 到 MemoryStore
        MvcResult approveResult = mvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "sessionId", SID, "query", "/memory approve " + pendingId))))
                .andExpect(status().isOk())
                .andReturn();
        String approveReply = approveResult.getResponse().getContentAsString();
        assertTrue(approveReply.contains("Approved #" + pendingId),
                "Web /memory approve 应返 ✓ Approved 标识,实际:" + approveReply);

        // ⑦ Fs 验证:approve 应从 pool 移除该 id + MemoryStore 有 approved entry
        // (pool 中其它 turn 产生的提案仍可能在,只验证 pendingId 这条不在了)
        assertFalse(pendingStore.get(pendingId).isPresent(),
                "approve 后 #" + pendingId + " 应不在 pending pool");
        var approved = memoryStore.list();
        assertTrue(approved.stream().anyMatch(m -> m.getName().equals("feedback-prefer-rg")),
                "Memory store 应含 approved entry");

        // 磁盘文件实际存在
        Path memFile = memoryTmpDir.resolve("feedback-prefer-rg.md");
        assertTrue(Files.exists(memFile), "approve 应在 fs 落盘 .md 文件");
        String content = Files.readString(memFile);
        assertTrue(content.contains("Always use rg"), "落盘内容应含 body");
    }

    @Test
    @DisplayName("E2E:/memory reject 走 Web → pending pool 移除,store 不写")
    void reject_via_web() throws Exception {
        // 直接塞一条 pending(跳过 reviewer 异步路径,只测 reject 命令)
        pendingStore.propose(MemoryFile.of("feedback-bad", MemoryFile.Type.FEEDBACK,
                "bad lesson", "low quality content"), "reviewer");
        long id = pendingStore.readAll().get(0).getId();

        MvcResult result = mvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "sessionId", SID, "query", "/memory reject " + id))))
                .andExpect(status().isOk())
                .andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Rejected #" + id));
        assertEquals(0, pendingStore.count(), "reject 后 pool 空");
        assertEquals(0, memoryStore.list().size(), "reject 不应写 store");
    }

    /** 简易 polling wait(避免依赖 Awaitility)。 */
    private static boolean pollUntil(java.util.function.BooleanSupplier check,
                                     long timeoutMs, long intervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.getAsBoolean()) return true;
            try {
                TimeUnit.MILLISECONDS.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return check.getAsBoolean();
    }
}
