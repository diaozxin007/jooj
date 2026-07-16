package com.xilidou.jooj;

import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.hook.HookManager;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.session.AgentLockProvider;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.slashcmd.SlashCommandRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CLI REPL 启动器 —— 容器装配完毕后进入交互循环。
 *
 * <p>s23 P1a(2026-07-16):把 REPL 主循环从 {@link AgentLoopHarness#repl} 搬进本类,
 * 让 harness 只关心 "single-turn domain execution",不再持有 CLI I/O 职责。
 *
 * <p>独立成 {@code @Component} + {@code @Profile("!test & !web & !tui")} 的关键原因:
 * <ul>
 *   <li>测试场景下 {@link org.springframework.boot.test.context.SpringBootTest @SpringBootTest}
 *       也会触发 {@link CommandLineRunner} 执行,如果 REPL 写在 {@link JoojApplication}
 *       自身上,所有 @SpringBootTest 都会卡在等 stdin —— 必须用 profile 把 CLI runner
 *       排除在测试外</li>
 *   <li>Web 模式下也不该跑 CLI runner —— 否则 Tomcat 启动后 jooj 主线程会卡在
 *       {@link Scanner#nextLine} 上,日志看着像"卡死"</li>
 *   <li><b>s23 新加</b>:{@code tui} profile 启用时走 TuiChannel,legacy REPL 让位</li>
 * </ul>
 *
 * <p>测试切到 {@code @ActiveProfiles("test")} 即可禁用本类(详见 application-test.yml)。
 * Web 模式启用 {@code web} profile 同样禁用本类。TUI 模式启用 {@code tui} 禁用本类。
 */
@Component
@Profile("!test & !web & !tui")
public class JoojCliRunner implements CommandLineRunner {

    private final AgentLoopHarness harness;
    private final SlashCommandRegistry slashCommands;
    private final HookManager hooks;
    private final AgentLockProvider lockProvider;
    private final SessionService sessionService;

    public JoojCliRunner(AgentLoopHarness harness,
                         SlashCommandRegistry slashCommands,
                         HookManager hooks,
                         AgentLockProvider lockProvider,
                         SessionService sessionService) {
        this.harness = harness;
        this.slashCommands = slashCommands;
        this.hooks = hooks;
        this.lockProvider = lockProvider;
        this.sessionService = sessionService;
    }

    @Override
    public void run(String... args) {
        System.out.println("s01: Agent Loop (Java)");
        System.out.println("输入问题,回车发送。/help 查看命令,q 退出。\n");

        // CLI REPL 走固定 cli-default session,跨进程重启历史保留。
        final String sessionId = Session.CLI_DEFAULT_ID;
        ReentrantLock lock = lockProvider.lockFor(sessionId);

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            while (true) {
                System.out.print("\033[36ms01 >> \033[0m");
                if (!scanner.hasNextLine()) break;

                String query = scanner.nextLine().strip();
                if (query.equalsIgnoreCase("q")
                        || query.equalsIgnoreCase("exit")
                        || query.isEmpty()) break;

                // Slash 命令 —— 走 registry,跳过 hooks / lock / processOneQuery。
                // 这些都是纯客户端动作,不进 LLM、不算并发请求。
                if (slashCommands != null && slashCommands.isCommand(query)) {
                    System.out.println(slashCommands.dispatch(query, sessionId));
                    System.out.println();
                    continue;
                }

                Optional<String> blocked = hooks.triggerUserPrompt(query);
                if (blocked.isPresent()) {
                    System.out.println("\033[31m⛔ Prompt blocked: " + blocked.get() + "\033[0m");
                    continue;
                }

                if (!lock.tryLock()) {
                    System.out.println("\033[33m⏳ Agent busy, please retry.\033[0m");
                    continue;
                }
                try {
                    // s22 架构审查(2026-07-13):todo 生命周期由 SessionHistoryCleared /
                    // SessionDeleted 事件驱动(TodoStore 直接 listen),不再需要每轮 fireOnNewSession。
                    harness.processOneQuery(sessionId, query);
                } finally {
                    lock.unlock();
                }

                printLastAssistantText(sessionService.loadHistory(sessionId));
                System.out.println();
            }
        }
    }

    /**
     * 打印最后一条 assistant 消息的纯文本。s23 P1a 从 AgentLoopHarness 搬来。
     *
     * <p><b>注意</b>:这份打印是 legacy CLI 兼容路径。P1b 之后 harness 出门发
     * {@code AssistantResponseCompleted} 事件,TUI channel 通过 event listener 拿到 reply;
     * legacy CLI 因为不接事件总线,继续用这个直读 session history 的路径。
     */
    private void printLastAssistantText(List<LlmMessage> history) {
        if (history.isEmpty()) return;
        LlmMessage last = history.get(history.size() - 1);
        if (last.getRole() != LlmRole.ASSISTANT || last.getContent() == null) return;
        for (LlmContent c : last.getContent()) {
            if (c instanceof LlmText t && t.getText() != null) {
                System.out.println(t.getText());
            }
        }
    }
}
