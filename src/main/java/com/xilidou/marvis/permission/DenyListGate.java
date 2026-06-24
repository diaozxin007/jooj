package com.xilidou.marvis.permission;

import com.xilidou.marvis.http.dto.ToolUseBlock;

import java.util.List;

/**
 * Gate 1：硬黑名单。
 *
 * <p>对应 Python s03 第 149 行的 {@code DENY_LIST}。
 * 命中后**立即 DENY**，不给用户做选择的机会（用户也不应该 yes 这些命令）。
 *
 * <p>当前只检查 bash 工具的 command 参数。其他工具（read/write/edit）的硬规则
 * 由各自的 {@code safePath()} 在 Tool 层做（path traversal 防御）。
 *
 * <p>黑名单匹配：子串包含。简单粗暴但够用。绕过方式（如 {@code rm  -rf  /}
 * 多空格）由 Gate 2 的 destructive keyword 兜底。
 */
public class DenyListGate implements PermissionGate {

    /**
     * 默认黑名单。来自 s03 加上常见绕过形式。
     */
    public static final List<String> DEFAULT_DENY_LIST = List.of(
            "rm -rf /",
            "sudo",
            "shutdown",
            "reboot",
            "mkfs",
            "dd if=",
            "> /dev/sda",
            "> /dev/sdb",
            ":(){:|:&};:"        // fork bomb
    );

    private final List<String> denyList;

    public DenyListGate() {
        this(DEFAULT_DENY_LIST);
    }

    public DenyListGate(List<String> denyList) {
        this.denyList = List.copyOf(denyList);
    }

    @Override
    public PermissionResult check(ToolUseBlock toolUse) {
        if (!"bash".equals(toolUse.getName())) {
            return PermissionResult.allow();
        }

        // bash 工具的 input 是 {"command": "..."}
        var input = toolUse.getInput();
        if (input == null || !input.has("command")) {
            return PermissionResult.allow();
        }
        String command = input.get("command").asText();

        for (String pattern : denyList) {
            if (command.contains(pattern)) {
                return PermissionResult.deny(
                        "Blocked by deny list: '" + pattern + "' is forbidden"
                );
            }
        }
        return PermissionResult.allow();
    }
}
