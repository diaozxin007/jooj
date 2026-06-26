package com.xilidou.jooj.permission;

import com.xilidou.jooj.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 权限管道：把多个 Gate 串起来跑。
 *
 * <p>对应 Python s03 第 184 行的 {@code check_permission()}。
 *
 * <p>检查顺序：
 * <ol>
 *   <li>{@link DenyListGate}（Gate 1）—— DENY 立即终止</li>
 *   <li>{@link RuleBasedGate}（Gate 2）—— 返回 ASK 时调 Gate 3</li>
 *   <li>{@link UserApprovalGate}（Gate 3）—— 把 ASK 升级为 ALLOW/DENY</li>
 * </ol>
 *
 * <p>Pipeline 结果只有 ALLOW / DENY 两态（ASK 已被 Gate 3 消费）。
 *
 * <p>典型用法（CLI 场景）：
 * <pre>
 *   PermissionPipeline pipeline = PermissionPipeline.defaultCli();
 *   PermissionResult result = pipeline.check(toolUse);
 *   if (result.isDeny()) {
 *       toolResults.add(ToolResultBlock.ofText(toolUse.getId(), result.getReason()));
 *       continue;
 *   }
 *   // 执行工具...
 * </pre>
 */
@Slf4j
public class PermissionPipeline {

    private final List<PermissionGate> earlyGates;   // Gate 1, 2
    private final UserApprovalGate approvalGate;     // Gate 3（特殊处理 ASK）

    /**
     * 全参构造器：完全自定义。
     *
     * @param earlyGates    Gate 1, 2 序列（顺序执行）
     * @param approvalGate  Gate 3（处理 ASK 决策）
     */
    public PermissionPipeline(List<PermissionGate> earlyGates, UserApprovalGate approvalGate) {
        this.earlyGates = List.copyOf(earlyGates);
        this.approvalGate = approvalGate;
    }

    /**
     * 默认 CLI 配置：DenyListGate + RuleBasedGate + ConsoleUserApprover。
     */
    public static PermissionPipeline defaultCli() {
        return new PermissionPipeline(
                List.of(new DenyListGate(), new RuleBasedGate()),
                new UserApprovalGate(new ConsoleUserApprover())
        );
    }

    /**
     * 测试场景：所有 ASK 都自动通过。
     */
    public static PermissionPipeline alwaysAllow() {
        return new PermissionPipeline(
                List.of(new DenyListGate(), new RuleBasedGate()),
                new UserApprovalGate(UserApprover.ALWAYS_ALLOW)
        );
    }

    /**
     * 测试场景：所有 ASK 都自动拒绝。
     */
    public static PermissionPipeline alwaysDeny() {
        return new PermissionPipeline(
                List.of(new DenyListGate(), new RuleBasedGate()),
                new UserApprovalGate(UserApprover.ALWAYS_DENY)
        );
    }

    /**
     * 检查一个工具调用。
     *
     * @return ALLOW（可以执行）或 DENY（拒绝执行）—— 不会返回 ASK
     */
    public PermissionResult check(ToolUseBlock toolUse) {
        // 先跑前面的 Gate
        for (PermissionGate gate : earlyGates) {
            PermissionResult result = gate.check(toolUse);

            if (result.isDeny()) {
                log.info("[Permission] DENY ({}): {}", toolUse.getName(), result.getReason());
                return result;
            }

            if (result.isAsk()) {
                // 触发 Gate 3
                PermissionResult userResult = approvalGate.askWithReason(toolUse, result.getReason());
                if (userResult.isDeny()) {
                    log.info("[Permission] User DENY ({}): {}", toolUse.getName(), userResult.getReason());
                } else {
                    log.info("[Permission] User ALLOW ({}): {}", toolUse.getName(), result.getReason());
                }
                return userResult;
            }
        }

        // 所有 Gate 都返回 ALLOW
        return PermissionResult.allow();
    }
}
