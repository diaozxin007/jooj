package com.xilidou.marvis.harness.permission;

import com.xilidou.marvis.harness.http.dto.ToolUseBlock;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * CLI 场景的 {@link UserApprover} 实现：阻塞式 [y/N] 提示。
 *
 * <p>对应 Python s03 第 176 行的 {@code ask_user()}。
 *
 * <p>输出示例：
 * <pre>
 *   ⚠  Potentially destructive command (matched 'rm '): rm -rf build/
 *      Tool: bash({command=rm -rf build/})
 *      Allow? [y/N]
 * </pre>
 *
 * <p>用 Scanner 而不是 BufferedReader 是因为：
 * <ul>
 *   <li>项目其他地方（{@link com.xilidou.marvis.harness.agent.AgentLoopHarness#repl}）
 *       也用 Scanner，风格一致</li>
 *   <li>Scanner 自带 UTF-8 处理，中文输入不会乱码</li>
 * </ul>
 *
 * <p>线程安全说明：本类**不是**线程安全的。如果 Loop 是多线程的，需要外部加锁
 * 或换实现。当前 Loop 是单线程同步的，所以没问题。
 */
public class ConsoleUserApprover implements UserApprover {

    private static final String YELLOW = "\033[33m";
    private static final String RESET = "\033[0m";

    private final Scanner scanner;
    private final PrintStream out;

    /**
     * 默认构造器：从 stdin 读，stdout 写。
     */
    public ConsoleUserApprover() {
        this(new Scanner(System.in, StandardCharsets.UTF_8), System.out);
    }

    /**
     * 测试用：可注入自定义 Scanner（包 String 输入）+ PrintStream（捕获输出）。
     */
    public ConsoleUserApprover(Scanner scanner, PrintStream out) {
        this.scanner = scanner;
        this.out = out;
    }

    @Override
    public boolean approve(ToolUseBlock toolUse, String reason) {
        out.println();
        out.println(YELLOW + "⚠  " + reason + RESET);
        out.println("   Tool: " + toolUse.getName() + "(" + toolUse.getInput() + ")");
        out.print("   Allow? [y/N] ");

        if (!scanner.hasNextLine()) {
            // EOF（pipe 结束 / Ctrl-D），保守拒绝
            return false;
        }
        String input = scanner.nextLine().strip().toLowerCase();
        return input.equals("y") || input.equals("yes");
    }
}
