package com.xilidou.jooj;

import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.slashcmd.SlashCommandRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * CLI 启动器 —— 容器装配完毕后调用 {@link AgentLoopHarness#repl()} 进入交互循环。
 *
 * <p>独立成 {@code @Component} + {@code @Profile("!test & !web")} 的关键原因:
 * <ul>
 *   <li>测试场景下 {@link org.springframework.boot.test.context.SpringBootTest @SpringBootTest}
 *       也会触发 {@link CommandLineRunner} 执行,如果 REPL 写在 {@link JoojApplication}
 *       自身上,所有 @SpringBootTest 都会卡在等 stdin —— 必须用 profile 把 CLI runner
 *       排除在测试外</li>
 *   <li>Web 模式下也不该跑 CLI runner —— 否则 Tomcat 启动后 jooj 主线程会卡在
 *       {@link AgentLoopHarness#repl()} 读 stdin 上,日志看着像"卡死"</li>
 * </ul>
 *
 * <p>测试切到 {@code @ActiveProfiles("test")} 即可禁用本类(详见 application-test.yml)。
 * Web 模式启用 {@code web} profile 同样禁用本类。
 */
@Component
@Profile("!test & !web")
public class JoojCliRunner implements CommandLineRunner {

    private final AgentLoopHarness harness;
    private final SlashCommandRegistry slashCommands;

    public JoojCliRunner(AgentLoopHarness harness, SlashCommandRegistry slashCommands) {
        this.harness = harness;
        this.slashCommands = slashCommands;
    }

    @Override
    public void run(String... args) {
        harness.repl(slashCommands);
    }
}
