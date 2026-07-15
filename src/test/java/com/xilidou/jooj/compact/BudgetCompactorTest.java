package com.xilidou.jooj.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link BudgetCompactor} (L3) 的核心行为。
 *
 * <p>6 个关键场景:
 * <ol>
 *   <li>无 tool_result 不动</li>
 *   <li>小内容(≤ maxToolResultBytes)不动</li>
 *   <li>大内容 → 落盘 + 替换为 stub,文件路径准确</li>
 *   <li>幂等:已是 stub 的不重复处理</li>
 *   <li>多个 tool_result 各落各的盘,文件名 = tool_call_id</li>
 *   <li>tool_call_id 含特殊字符 → 文件名 sanitize 后不会路径穿越</li>
 * </ol>
 *
 * <p>P2 Step G:fixture 迁到 canonical {@link LlmMessage / LlmToolCall / LlmToolResult}(TOOL 一等 role)。
 */
class BudgetCompactorTest {

    private static LlmMessage userToolResult(String id, String content) {
        return LlmMessage.toolResults(new ArrayList<>(List.of(LlmToolResult.success(id, content))));
    }

    private static LlmToolResult firstToolResult(LlmMessage m) {
        return (LlmToolResult) m.getContent().get(0);
    }

    private static String hugeContent(int len) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < len) sb.append("yyyy ");
        return sb.toString();
    }

    /** 基于 @TempDir 构造 config:阈值小、目录是临时的、其他 L1/L2 字段不影响 L3。*/
    private static CompactConfig configWithDir(Path tempDir, int maxBytes) {
        return new CompactConfig(50, 3, 3, 120, maxBytes, tempDir);
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 1:无 tool_result 不动
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget should not modify when no tool_results present")
    void budget_should_skip_when_no_tool_results(@TempDir Path tempDir) {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("hello"));
        messages.add(LlmMessage.userText("world"));

        boolean changed = budget.apply(messages);

        assertFalse(changed);
        // 临时目录应该是空的(没创建子文件)
        try (var stream = Files.newDirectoryStream(tempDir)) {
            assertFalse(stream.iterator().hasNext(), "无 tool_result 时不应有任何文件落盘");
        } catch (IOException e) {
            fail(e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 2:小内容(≤ maxToolResultBytes)不动
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget should not persist small tool_results")
    void budget_should_not_persist_small_results(@TempDir Path tempDir) {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 1000));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("query"));
        messages.add(userToolResult("tu_1", "small content (only 20 chars)"));
        messages.add(userToolResult("tu_2", hugeContent(500)));  // 仍 < 1000

        boolean changed = budget.apply(messages);

        assertFalse(changed, "所有内容都 ≤ maxToolResultBytes,不应触发");
        // 内容原封不动
        assertEquals("small content (only 20 chars)", firstToolResult(messages.get(1)).getOutput());
        assertEquals(hugeContent(500), firstToolResult(messages.get(2)).getOutput());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3:大内容 → 落盘 + stub 替换
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget should persist large content to disk and replace with stub")
    void budget_should_persist_large_content(@TempDir Path tempDir) throws IOException {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("query"));
        String original = hugeContent(500);  // > 100
        messages.add(userToolResult("tu_42", original));

        boolean changed = budget.apply(messages);

        assertTrue(changed, "500 > 100 应该触发落盘");

        // 验证文件存在 + 内容完整
        Path expectedFile = tempDir.resolve("tu_42.txt");
        assertTrue(Files.exists(expectedFile), "应在 tempDir 下创建 tu_42.txt");
        assertEquals(original, Files.readString(expectedFile, StandardCharsets.UTF_8),
                "落盘文件内容必须 = 原 tool_result content");

        // 验证 stub 替换
        String stub = firstToolResult(messages.get(1)).getOutput();
        assertTrue(stub.startsWith(BudgetCompactor.STUB_PREFIX),
                "stub 必须以 STUB_PREFIX 开头,实际: " + stub);
        assertTrue(stub.contains(expectedFile.toAbsolutePath().toString()),
                "stub 必须包含落盘文件的绝对路径,实际: " + stub);
        assertTrue(stub.contains("Read the file to see full content."),
                "stub 必须给模型重读指引");
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 4:幂等性 — 已是 stub 的不重复处理
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget should be idempotent: stubbed content not re-persisted")
    void budget_should_be_idempotent(@TempDir Path tempDir) throws IOException {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("q"));
        messages.add(userToolResult("tu_1", hugeContent(500)));

        // 第一次:正常落盘
        assertTrue(budget.apply(messages));
        String firstStub = firstToolResult(messages.get(1)).getOutput();

        // 第二次:stub 已经在 content 里(且 stub 长度可能 > 100),
        //       但 STUB_PREFIX 检查必须挡住,不重复落盘
        assertFalse(budget.apply(messages),
                "第二次 apply 不应再次落盘:已经是 stub");

        // 验证 stub 文本完全一样(未被覆盖)
        assertEquals(firstStub, firstToolResult(messages.get(1)).getOutput());

        // 临时目录里应该只有 1 个文件(没把 stub 当原文重新写)
        try (var stream = Files.newDirectoryStream(tempDir)) {
            int count = 0;
            for (var p : stream) count++;
            assertEquals(1, count, "幂等保证只落盘 1 次");
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 5:多 tool_result 各落各的盘
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget should persist multiple large results to separate files")
    void budget_should_handle_multiple_results(@TempDir Path tempDir) {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("q"));
        for (int i = 0; i < 4; i++) {
            messages.add(userToolResult("tu_" + i, hugeContent(500)));
        }

        boolean changed = budget.apply(messages);

        assertTrue(changed);
        for (int i = 0; i < 4; i++) {
            assertTrue(Files.exists(tempDir.resolve("tu_" + i + ".txt")),
                    "tu_" + i + ".txt 应该存在");
            String stub = firstToolResult(messages.get(i + 1)).getOutput();
            assertTrue(stub.startsWith(BudgetCompactor.STUB_PREFIX));
            assertTrue(stub.contains("tu_" + i + ".txt"),
                    "tu_" + i + " 的 stub 应该指向 tu_" + i + ".txt");
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:tool_call_id 路径穿越防御
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget should sanitize tool_call_id to prevent path traversal")
    void budget_should_sanitize_tool_use_id(@TempDir Path tempDir) {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.userText("q"));
        // 恶意 tool_call_id:试图跳出 tempDir 写到 ../etc/passwd
        messages.add(userToolResult("../../../etc/passwd", hugeContent(500)));

        assertTrue(budget.apply(messages));

        // 文件应该落在 tempDir 内,不应有 ../etc/passwd 这种路径
        try (var stream = Files.walk(tempDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                assertTrue(p.startsWith(tempDir),
                        "文件必须落在 tempDir 内,实际: " + p);
            });
        } catch (IOException e) {
            fail(e);
        }
        // 文件名应该是 sanitize 过的(下划线替换非法字符)
        try (var stream = Files.newDirectoryStream(tempDir)) {
            boolean foundSanitized = false;
            for (Path p : stream) {
                String name = p.getFileName().toString();
                // 不应包含 / 或 ..(已被 sanitize 替换为 _)
                assertFalse(name.contains(".."), "文件名不应含 .. : " + name);
                assertFalse(name.contains("/"), "文件名不应含 / : " + name);
                if (name.endsWith(".txt")) foundSanitized = true;
            }
            assertTrue(foundSanitized, "应该有一个 sanitize 后的 .txt 文件");
        } catch (IOException e) {
            fail(e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 7:写盘失败时优雅降级(不抛异常)
    // ─────────────────────────────────────────────────────────────

    /**
     * 回归防线:曾经的死循环。BudgetCompactor 每轮 apply 都对超阈值 tool_result 落盘换 stub,
     * 但如果那个 tool_result 恰好是 read_file 读**上一轮 stub 文件**返回的原文,又会被拆一次
     * (新 tool_call_id → 新文件),形成 read_file → 拆 → read_file → 拆 的 ping-pong,
     * 至少在实测里跑掉几十次 API 调用直到某次输出恰好 ≤ 阈值。
     *
     * <p>修复思路:apply 时把 tool_call 的调用参数带上——若 tool_call 是 {@code read_file} 或
     * {@code bash cat/head/tail} 读位于 taskOutputDir 之下的路径,即使内容超阈值也不再拆,
     * 直接把原文送给 LLM 让它自己决定截断。
     */
    @Test
    @DisplayName("regression: 不能对 read_file 读回自身 stub 的结果再次拆分")
    void budget_should_not_repersist_self_readback(@TempDir Path tempDir) throws Exception {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        ObjectMapper mapper = new ObjectMapper();

        // 模拟:assistant 上一轮调 read_file 读了 tempDir 下的一个 stub 文件
        String stubPath = tempDir.resolve("previous_dump.txt").toAbsolutePath().toString();
        LlmToolCall readCall = new LlmToolCall(
                "tu_readback",
                "read_file",
                mapper.readTree("{\"path\":\"" + stubPath.replace("\\", "\\\\") + "\"}")
        );
        LlmMessage asst = LlmMessage.assistant(new ArrayList<>(List.<LlmContent>of(readCall)));

        // read_file 返回的 tool_result 是原文的完整内容(> 阈值)
        String origContent = hugeContent(500);
        LlmMessage usr = userToolResult("tu_readback", origContent);

        List<LlmMessage> messages = new ArrayList<>(List.of(asst, usr));
        boolean changed = budget.apply(messages);

        assertFalse(changed, "读回 stub 文件的 tool_result 必须跳过,不再拆");
        // content 保持原文,没被替换成新 stub
        assertEquals(origContent, firstToolResult(messages.get(1)).getOutput(),
                "原文应完整送到 LLM,不该换成 stub 让它再读一次");
        // 临时目录没有新落盘文件
        try (var stream = Files.newDirectoryStream(tempDir)) {
            assertFalse(stream.iterator().hasNext(),
                    "self-readback 场景不应产生落盘文件");
        }
    }

    @Test
    @DisplayName("regression: bash cat 读回 stub 文件也走同一防御")
    void budget_should_not_repersist_bash_cat_readback(@TempDir Path tempDir) throws Exception {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        ObjectMapper mapper = new ObjectMapper();

        String stubPath = tempDir.resolve("dump.txt").toAbsolutePath().toString();
        LlmToolCall bashCall = new LlmToolCall(
                "tu_bash_cat",
                "bash",
                mapper.readTree("{\"command\":\"cat " + stubPath.replace("\\", "\\\\") + "\"}")
        );
        LlmMessage asst = LlmMessage.assistant(new ArrayList<>(List.<LlmContent>of(bashCall)));
        LlmMessage usr = userToolResult("tu_bash_cat", hugeContent(500));

        boolean changed = budget.apply(new ArrayList<>(List.of(asst, usr)));

        assertFalse(changed, "bash cat 读 stub 也算 self-readback");
    }

    @Test
    @DisplayName("normal read_file(非 taskOutputDir 路径)仍会被拆")
    void budget_should_still_persist_normal_read_file(@TempDir Path tempDir) throws Exception {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        ObjectMapper mapper = new ObjectMapper();

        // 用户真的读了一个 workdir 下的普通大文件——应该拆
        LlmToolCall readCall = new LlmToolCall(
                "tu_normal_read",
                "read_file",
                mapper.readTree("{\"path\":\"/tmp/some/user/file.log\"}")
        );
        LlmMessage asst = LlmMessage.assistant(new ArrayList<>(List.<LlmContent>of(readCall)));
        LlmMessage usr = userToolResult("tu_normal_read", hugeContent(500));

        boolean changed = budget.apply(new ArrayList<>(List.of(asst, usr)));

        assertTrue(changed, "读普通文件的大输出仍应被拆,只有读 taskOutputDir 才豁免");
        assertTrue(Files.exists(tempDir.resolve("tu_normal_read.txt")));
    }
}
