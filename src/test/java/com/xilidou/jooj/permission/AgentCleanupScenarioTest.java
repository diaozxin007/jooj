package com.xilidou.jooj.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JacksonConfig;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实场景模拟：Agent 自动清理项目。
 *
 * <p>场景：用户让 Claude "帮我清理项目"，Claude 依次发出 5 个工具调用，
 * 权限管道对每个调用做出不同决策。
 *
 * <p>验证三道闸门在真实任务流里的协同行为：
 * <ul>
 *   <li>只读操作不打扰用户</li>
 *   <li>危险操作先问用户</li>
 *   <li>黑名单操作直接拦截，连问都不问</li>
 *   <li>用户说"不"时 Agent 跳过该步骤</li>
 * </ul>
 */
@DisplayName("场景测试：Agent 自动清理项目")
class AgentCleanupScenarioTest {

    private static final ObjectMapper JSON = JacksonConfig.newMapper();

    /** 记录所有 ASK 事件 —— 模拟 UserApprover 的决策日志 */
    private final List<String> askLog = new ArrayList<>();

    private static ToolUseBlock toolUse(String name, Map<String, Object> input) {
        JsonNode inputNode = JSON.valueToTree(input);
        return new ToolUseBlock("toolu_test", name, inputNode);
    }

    // ──────────────────────────────────────────────────────────
    //  Step 1: find . -name "*.class"  →  只读，直接 ALLOW
    // ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Step1: find .class 文件（只读）→ 直接放行，不打断用户")
    void step1_find_class_files_is_allowed() {
        PermissionPipeline pipeline = buildPipeline(/* userSaysYes= */ true);

        PermissionResult result = pipeline.check(
                toolUse("bash", Map.of("command", "find . -name \"*.class\" | head -20"))
        );

        assertTrue(result.isAllow());
        assertTrue(askLog.isEmpty(), "只读操作不应打断用户，askLog 应为空");
    }

    // ──────────────────────────────────────────────────────────
    //  Step 2: rm -rf target/  →  Gate2 ASK，用户允许 → ALLOW
    // ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Step2: rm -rf target/（用户允许）→ 审批后放行")
    void step2_rm_target_user_approves() {
        PermissionPipeline pipeline = buildPipeline(/* userSaysYes= */ true);

        PermissionResult result = pipeline.check(
                toolUse("bash", Map.of("command", "rm -rf target/"))
        );

        assertTrue(result.isAllow());
        assertEquals(1, askLog.size(), "应触发一次 ASK");
        assertTrue(askLog.get(0).contains("rm "), "ASK 原因应包含 'rm '");
    }

    // ──────────────────────────────────────────────────────────
    //  Step 2b: rm -rf target/  →  Gate2 ASK，用户拒绝 → DENY
    // ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Step2b: rm -rf target/（用户拒绝）→ 跳过，Agent 继续下一步")
    void step2_rm_target_user_denies() {
        PermissionPipeline pipeline = buildPipeline(/* userSaysYes= */ false);

        PermissionResult result = pipeline.check(
                toolUse("bash", Map.of("command", "rm -rf target/"))
        );

        assertTrue(result.isDeny());
        assertTrue(result.getReason().contains("User denied"));
        assertEquals(1, askLog.size(), "被拒绝前也触发了 ASK");
    }

    // ──────────────────────────────────────────────────────────
    //  Step 3: write_file 到 workspace 外  →  Gate2 ASK（路径越界）
    // ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Step3: 写文件到 workspace 外（备份到上级目录）→ ASK 路径越界")
    void step3_write_outside_workspace_asks() {
        PermissionPipeline pipeline = buildPipeline(/* userSaysYes= */ true);

        PermissionResult result = pipeline.check(
                toolUse("write_file", Map.of(
                        "path", "../backup/project.tar.gz",
                        "content", "binary-data"
                ))
        );

        // 用户同意了，所以 ALLOW
        assertTrue(result.isAllow());
        assertEquals(1, askLog.size());
        assertTrue(askLog.get(0).contains("outside workspace"));
    }

    // ──────────────────────────────────────────────────────────
    //  Step 4: sudo rm /var/log/app.log  →  Gate1 DENY，不问用户
    // ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Step4: sudo 命令 → Gate1 硬拦，用户没机会审批")
    void step4_sudo_is_hard_denied() {
        // 即使 userSaysYes=true，Gate1 也不会让 Gate3 介入
        PermissionPipeline pipeline = buildPipeline(/* userSaysYes= */ true);

        PermissionResult result = pipeline.check(
                toolUse("bash", Map.of("command", "sudo rm /var/log/app.log"))
        );

        assertTrue(result.isDeny());
        assertTrue(result.getReason().contains("sudo"), "拒绝原因应说明是 sudo");
        assertTrue(askLog.isEmpty(), "Gate1 拦截，Gate3 不介入，用户没被打扰");
    }

    // ──────────────────────────────────────────────────────────
    //  Step 5: git reset --hard HEAD~1  →  Gate2 ASK（危险 git）
    // ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Step5: git reset --hard（丢失提交）→ ASK，用户拒绝")
    void step5_git_reset_hard_user_denies() {
        PermissionPipeline pipeline = buildPipeline(/* userSaysYes= */ false);

        PermissionResult result = pipeline.check(
                toolUse("bash", Map.of("command", "git reset --hard HEAD~1"))
        );

        assertTrue(result.isDeny());
        assertEquals(1, askLog.size());
        assertTrue(askLog.get(0).contains("git reset --hard"));
    }

    // ──────────────────────────────────────────────────────────
    //  完整流程模拟：5 步全跑，统计放行/拒绝数量
    // ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("完整清理流程：5步，用户对所有 ASK 都说 YES → 4 ALLOW + 1 DENY(sudo)")
    void full_cleanup_flow_user_approves_all() {
        PermissionPipeline pipeline = buildPipeline(/* userSaysYes= */ true);

        List<ToolUseBlock> agentPlan = List.of(
                toolUse("bash",       Map.of("command", "find . -name \"*.class\"")),          // Step1 只读
                toolUse("bash",       Map.of("command", "rm -rf target/")),                     // Step2 rm
                toolUse("write_file", Map.of("path", "../backup.tar", "content", "data")),      // Step3 越界写
                toolUse("bash",       Map.of("command", "sudo rm /var/log/app.log")),           // Step4 sudo
                toolUse("bash",       Map.of("command", "git reset --hard HEAD~1"))             // Step5 危险git
        );

        long allowed = agentPlan.stream().map(pipeline::check).filter(PermissionResult::isAllow).count();
        long denied  = agentPlan.stream().map(pipeline::check).filter(PermissionResult::isDeny).count();

        assertEquals(4, allowed, "Step1/2/3/5(yes) 应该被放行");
        assertEquals(1, denied,  "只有 Step4 sudo 应该被直接拒绝");
    }

    @Test
    @DisplayName("完整清理流程：5步，用户对所有 ASK 都说 NO → 1 ALLOW + 4 DENY")
    void full_cleanup_flow_user_denies_all() {
        PermissionPipeline pipeline = buildPipeline(/* userSaysYes= */ false);

        List<ToolUseBlock> agentPlan = List.of(
                toolUse("bash",       Map.of("command", "find . -name \"*.class\"")),
                toolUse("bash",       Map.of("command", "rm -rf target/")),
                toolUse("write_file", Map.of("path", "../backup.tar", "content", "data")),
                toolUse("bash",       Map.of("command", "sudo rm /var/log/app.log")),
                toolUse("bash",       Map.of("command", "git reset --hard HEAD~1"))
        );

        long allowed = agentPlan.stream().map(pipeline::check).filter(PermissionResult::isAllow).count();
        long denied  = agentPlan.stream().map(pipeline::check).filter(PermissionResult::isDeny).count();

        assertEquals(1, allowed, "只有 Step1 只读查询直接放行");
        assertEquals(4, denied,  "Step2/3/4/5 全被拒");
    }

    // ──────────────────────────────────────────────────────────
    //  辅助：构建 Pipeline，注入可记录日志的 UserApprover
    // ──────────────────────────────────────────────────────────
    private PermissionPipeline buildPipeline(boolean userSaysYes) {
        askLog.clear();
        UserApprover loggingApprover = (toolUse, reason) -> {
            askLog.add(reason);   // 记录每次 ASK 的原因
            return userSaysYes;
        };
        return new PermissionPipeline(
                List.of(new DenyListGate(), new RuleBasedGate()),
                new UserApprovalGate(loggingApprover)
        );
    }
}
