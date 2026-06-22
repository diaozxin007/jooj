package com.xilidou.marvis.harness.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.harness.JacksonConfig;
import com.xilidou.marvis.harness.http.dto.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 Permission 三道闸门的核心行为。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>{@link DenyListGateTest} - Gate 1 硬黑名单</li>
 *   <li>{@link RuleBasedGateTest} - Gate 2 规则匹配</li>
 *   <li>{@link PipelineTest} - 三道闸门协同</li>
 * </ul>
 */
class PermissionPipelineTest {

    private static final ObjectMapper JSON = JacksonConfig.newMapper();

    /**
     * 构造一个 ToolUseBlock 的便利方法（测试 fixture）。
     */
    private static ToolUseBlock toolUse(String name, Map<String, Object> input) {
        JsonNode inputNode = JSON.valueToTree(input);
        return new ToolUseBlock("toolu_test", name, inputNode);
    }

    // ────────────────────────────────────────────────────────────
    //  Gate 1: DenyListGate
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Gate 1: DenyListGate")
    class DenyListGateTest {

        private final DenyListGate gate = new DenyListGate();

        @Test
        @DisplayName("rm -rf / → DENY")
        void blocks_rm_rf_root() {
            PermissionResult result = gate.check(toolUse("bash", Map.of("command", "rm -rf /")));
            assertTrue(result.isDeny());
            assertTrue(result.getReason().contains("rm -rf /"));
        }

        @Test
        @DisplayName("sudo → DENY")
        void blocks_sudo() {
            PermissionResult result = gate.check(toolUse("bash", Map.of("command", "sudo apt install x")));
            assertTrue(result.isDeny());
        }

        @Test
        @DisplayName("ls -la → ALLOW")
        void allows_safe_command() {
            PermissionResult result = gate.check(toolUse("bash", Map.of("command", "ls -la")));
            assertTrue(result.isAllow());
        }

        @Test
        @DisplayName("非 bash 工具 → ALLOW（DenyList 只管 bash）")
        void allows_non_bash_tools() {
            PermissionResult result = gate.check(toolUse("read_file", Map.of("path", "rm -rf /")));
            // 即使 path 字段含恶意字符串，read_file 不属于 bash，DenyList 不管
            assertTrue(result.isAllow());
        }

        @Test
        @DisplayName("自定义黑名单")
        void custom_deny_list() {
            DenyListGate custom = new DenyListGate(List.of("custom-evil"));
            PermissionResult denied = custom.check(toolUse("bash", Map.of("command", "do custom-evil now")));
            PermissionResult allowed = custom.check(toolUse("bash", Map.of("command", "rm -rf /")));

            assertTrue(denied.isDeny());
            assertTrue(allowed.isAllow(), "自定义黑名单替换默认，rm -rf / 不在自定义里");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Gate 2: RuleBasedGate
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Gate 2: RuleBasedGate")
    class RuleBasedGateTest {

        private final RuleBasedGate gate = new RuleBasedGate();

        @Test
        @DisplayName("write_file 到 workspace 外 → ASK")
        void asks_for_write_outside_workspace() {
            PermissionResult result = gate.check(toolUse("write_file", Map.of(
                    "path", "../escape.txt",
                    "content", "evil"
            )));
            assertTrue(result.isAsk());
            assertTrue(result.getReason().contains("outside workspace"));
        }

        @Test
        @DisplayName("write_file 到 workspace 内 → ALLOW")
        void allows_write_inside_workspace() {
            PermissionResult result = gate.check(toolUse("write_file", Map.of(
                    "path", "src/Main.java",
                    "content", "// ok"
            )));
            assertTrue(result.isAllow());
        }

        @Test
        @DisplayName("bash rm → ASK（destructive 关键字）")
        void asks_for_destructive_bash() {
            PermissionResult result = gate.check(toolUse("bash", Map.of(
                    "command", "rm -rf build/"
            )));
            assertTrue(result.isAsk());
            assertTrue(result.getReason().contains("rm "));
        }

        @Test
        @DisplayName("bash chmod 777 → ASK")
        void asks_for_chmod_777() {
            PermissionResult result = gate.check(toolUse("bash", Map.of(
                    "command", "chmod 777 secret.txt"
            )));
            assertTrue(result.isAsk());
        }

        @Test
        @DisplayName("bash 'wc -l' → ALLOW（只读统计不该被拦，曾因误拦被移除）")
        void allows_wc_l_readonly_stats() {
            // 历史教训：wc -l 是只读统计，曾被加进 destructive keywords 想拦
            // "超大文件统计"，但导致合理任务被反复打断（s06 端到端测试中等了 3'30"）。
            // 已移除——这个测试锁定不会复发。
            PermissionResult result = gate.check(toolUse("bash", Map.of(
                    "command", "wc -l README.md"
            )));
            assertTrue(result.isAllow(), "wc -l 是只读统计，必须 ALLOW；实际：" + result.getDecision());
        }

        @Test
        @DisplayName("bash 'find . | wc -l' → ALLOW（管道里的 wc 也不该被拦）")
        void allows_pipe_wc_l() {
            PermissionResult result = gate.check(toolUse("bash", Map.of(
                    "command", "find . -name '*.java' | wc -l"
            )));
            assertTrue(result.isAllow());
        }

        @Test
        @DisplayName("bash echo hello → ALLOW")
        void allows_safe_bash() {
            PermissionResult result = gate.check(toolUse("bash", Map.of(
                    "command", "echo hello"
            )));
            assertTrue(result.isAllow());
        }

        @Test
        @DisplayName("read_file 不触发任何规则 → ALLOW")
        void allows_read_file() {
            PermissionResult result = gate.check(toolUse("read_file", Map.of("path", "any.txt")));
            assertTrue(result.isAllow());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Pipeline: 三道闸门串起来
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PermissionPipeline 集成")
    class PipelineTest {

        @Test
        @DisplayName("Gate 1 DENY → Pipeline 直接返回 DENY，不问用户")
        void gate1_short_circuits() {
            PermissionPipeline pipeline = PermissionPipeline.alwaysAllow();    // Gate 3 always yes
            PermissionResult result = pipeline.check(toolUse("bash", Map.of("command", "rm -rf /")));

            assertTrue(result.isDeny(), "Gate 1 命中应该直接 DENY，不走到 Gate 3");
        }

        @Test
        @DisplayName("Gate 2 ASK + 用户允许 → ALLOW")
        void gate2_ask_user_yes() {
            PermissionPipeline pipeline = PermissionPipeline.alwaysAllow();
            PermissionResult result = pipeline.check(toolUse("bash", Map.of("command", "rm build/x")));

            assertTrue(result.isAllow());
        }

        @Test
        @DisplayName("Gate 2 ASK + 用户拒绝 → DENY")
        void gate2_ask_user_no() {
            PermissionPipeline pipeline = PermissionPipeline.alwaysDeny();
            PermissionResult result = pipeline.check(toolUse("bash", Map.of("command", "rm build/x")));

            assertTrue(result.isDeny());
            assertTrue(result.getReason().contains("User denied"));
        }

        @Test
        @DisplayName("无规则触发 → ALLOW（所有 Gate 都过）")
        void all_gates_pass() {
            PermissionPipeline pipeline = PermissionPipeline.alwaysDeny();    // 即使 always deny
            PermissionResult result = pipeline.check(toolUse("bash", Map.of("command", "ls -la")));

            assertTrue(result.isAllow(), "没规则触发就不会进 Gate 3，always deny 也无所谓");
        }

        @Test
        @DisplayName("自定义 UserApprover：根据原因决定")
        void custom_approver_logic() {
            UserApprover smart = (toolUse, reason) -> reason.contains("rm ") && !reason.contains("/");
            // 接受不带 / 的 rm，拒绝带 / 的 rm

            PermissionPipeline pipeline = new PermissionPipeline(
                    List.of(new DenyListGate(), new RuleBasedGate()),
                    new UserApprovalGate(smart)
            );

            // "rm build/" 含 /，拒绝
            assertTrue(pipeline.check(toolUse("bash", Map.of("command", "rm build/"))).isDeny());

            // "rm test.txt" 不含 /，接受
            assertTrue(pipeline.check(toolUse("bash", Map.of("command", "rm test.txt"))).isAllow());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  ConsoleUserApprover（输入注入测试）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ConsoleUserApprover: 'y' → 允许")
    void console_approver_accepts_yes() throws Exception {
        java.util.Scanner scanner = new java.util.Scanner("y\n");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ConsoleUserApprover approver = new ConsoleUserApprover(scanner, new java.io.PrintStream(out));

        boolean approved = approver.approve(
                toolUse("bash", Map.of("command", "rm x")),
                "test reason"
        );

        assertTrue(approved);
        assertTrue(out.toString().contains("test reason"), "should print reason");
    }

    @Test
    @DisplayName("ConsoleUserApprover: 'N' / 空行 / EOF → 拒绝")
    void console_approver_rejects_anything_else() {
        for (String input : List.of("N\n", "no\n", "\n", "garbage\n")) {
            java.util.Scanner scanner = new java.util.Scanner(input);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ConsoleUserApprover approver = new ConsoleUserApprover(scanner, new java.io.PrintStream(out));

            boolean approved = approver.approve(toolUse("bash", Map.of("command", "x")), "r");
            assertFalse(approved, "input=" + input.trim() + " should be rejected");
        }
    }
}
