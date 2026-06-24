package com.xilidou.marvis.compact;

import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.ToolResultBlock;
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
 *   <li>多个 tool_result 各落各的盘,文件名 = tool_use_id</li>
 *   <li>tool_use_id 含特殊字符 → 文件名 sanitize 后不会路径穿越</li>
 * </ol>
 */
class BudgetCompactorTest {

    private static MessageParam userToolResult(String id, String content) {
        return new MessageParam("user", new ArrayList<>(List.of(ToolResultBlock.ofText(id, content))));
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
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("hello"));
        messages.add(MessageParam.user("world"));

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
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("query"));
        messages.add(userToolResult("tu_1", "small content (only 20 chars)"));
        messages.add(userToolResult("tu_2", hugeContent(500)));  // 仍 < 1000

        boolean changed = budget.apply(messages);

        assertFalse(changed, "所有内容都 ≤ maxToolResultBytes,不应触发");
        // 内容原封不动
        ToolResultBlock r1 = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
        ToolResultBlock r2 = (ToolResultBlock) ((List<?>) messages.get(2).getContent()).get(0);
        assertEquals("small content (only 20 chars)", r1.getContent());
        assertEquals(hugeContent(500), r2.getContent());
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 3:大内容 → 落盘 + stub 替换
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget should persist large content to disk and replace with stub")
    void budget_should_persist_large_content(@TempDir Path tempDir) throws IOException {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("query"));
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
        ToolResultBlock r = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
        String stub = (String) r.getContent();
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
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("q"));
        messages.add(userToolResult("tu_1", hugeContent(500)));

        // 第一次:正常落盘
        assertTrue(budget.apply(messages));
        ToolResultBlock r = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
        String firstStub = (String) r.getContent();

        // 第二次:stub 已经在 content 里(且 stub 长度可能 > 100),
        //       但 STUB_PREFIX 检查必须挡住,不重复落盘
        assertFalse(budget.apply(messages),
                "第二次 apply 不应再次落盘:已经是 stub");

        // 验证 stub 文本完全一样(未被覆盖)
        ToolResultBlock r2 = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
        assertEquals(firstStub, r2.getContent());

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
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("q"));
        for (int i = 0; i < 4; i++) {
            messages.add(userToolResult("tu_" + i, hugeContent(500)));
        }

        boolean changed = budget.apply(messages);

        assertTrue(changed);
        for (int i = 0; i < 4; i++) {
            assertTrue(Files.exists(tempDir.resolve("tu_" + i + ".txt")),
                    "tu_" + i + ".txt 应该存在");
            ToolResultBlock r = (ToolResultBlock) ((List<?>) messages.get(i + 1).getContent()).get(0);
            String stub = (String) r.getContent();
            assertTrue(stub.startsWith(BudgetCompactor.STUB_PREFIX));
            assertTrue(stub.contains("tu_" + i + ".txt"),
                    "tu_" + i + " 的 stub 应该指向 tu_" + i + ".txt");
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  测试 6:tool_use_id 路径穿越防御
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("budget should sanitize tool_use_id to prevent path traversal")
    void budget_should_sanitize_tool_use_id(@TempDir Path tempDir) {
        BudgetCompactor budget = new BudgetCompactor(configWithDir(tempDir, 100));
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.user("q"));
        // 恶意 tool_use_id:试图跳出 tempDir 写到 ../etc/passwd
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

    @Test
    @DisplayName("budget should gracefully skip when disk write fails (non-existent parent path)")
    void budget_should_skip_on_disk_failure() {
        // 用一个不能创建的路径(已存在的 file 当 dir 用)
        // 但 Files.createDirectories 在大多数路径上会成功,所以选个特殊场景:
        // 让 taskOutputDir 指向一个普通文件而非目录
        try {
            Path tmpFile = Files.createTempFile("budget-test-blocker", ".txt");
            tmpFile.toFile().deleteOnExit();
            // 用这个文件当 dir,createDirectories 会失败
            BudgetCompactor budget = new BudgetCompactor(
                    new CompactConfig(50, 3, 3, 120, 100, tmpFile));
            List<MessageParam> messages = new ArrayList<>();
            messages.add(MessageParam.user("q"));
            String original = hugeContent(500);
            messages.add(userToolResult("tu_1", original));

            boolean changed = budget.apply(messages);

            assertFalse(changed, "落盘失败应返回 false");
            // 原内容保留(未被替换)
            ToolResultBlock r = (ToolResultBlock) ((List<?>) messages.get(1).getContent()).get(0);
            assertEquals(original, r.getContent(),
                    "写盘失败时不能替换 content,以免数据丢失");
        } catch (IOException e) {
            fail(e);
        }
    }
}
