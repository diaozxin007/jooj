package com.xilidou.marvis.permission;

import com.xilidou.marvis.http.dto.ToolUseBlock;

/**
 * 用户审批接口。Gate 3 通过这个接口去"问用户"。
 *
 * <p>抽象掉具体交互方式，让审批可以走多种渠道：
 * <ul>
 *   <li>CLI 场景：{@link ConsoleUserApprover} - 阻塞式 Scanner [y/N]</li>
 *   <li>测试场景：传一个 {@code (toolUse, reason) -> true} lambda</li>
 *   <li>未来生产：可换 Web 弹窗 / IM 推送 / 邮件审批</li>
 * </ul>
 *
 * <p>设计理由：你之前选了"Scanner 阻塞式审批"，但**抽象化的成本几乎为零**
 * （一个接口 + 一个 lambda），却为 Week 12 部署铺好了扩展点。
 */
@FunctionalInterface
public interface UserApprover {

    /**
     * 询问用户是否允许执行。
     *
     * @param toolUse 想要执行的工具
     * @param reason  为什么需要审批（来自 Gate 2 的规则匹配原因）
     * @return true = 允许，false = 拒绝
     */
    boolean approve(ToolUseBlock toolUse, String reason);

    /**
     * 默认实现：永远拒绝。用于安全默认值（未配置 approver 时不让通过 ASK）。
     */
    UserApprover ALWAYS_DENY = (toolUse, reason) -> false;

    /**
     * 测试用：永远允许。
     */
    UserApprover ALWAYS_ALLOW = (toolUse, reason) -> true;
}
