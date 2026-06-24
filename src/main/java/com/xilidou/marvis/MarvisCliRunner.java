package com.xilidou.marvis;

import com.xilidou.marvis.agent.AgentLoopHarness;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * CLI 启动器 —— 容器装配完毕后调用 {@link AgentLoopHarness#repl()} 进入交互循环。
 *
 * <p>独立成 {@code @Component} + {@code @Profile("!test")} 的关键原因:
 * 测试场景下 {@link org.springframework.boot.test.context.SpringBootTest @SpringBootTest}
 * 也会触发 {@link CommandLineRunner} 执行,如果 REPL 写在 {@link MarvisApplication}
 * 自身上,所有 @SpringBootTest 都会卡在等 stdin —— 必须用 profile 把 CLI runner
 * 排除在测试外。
 *
 * <p>测试切到 {@code @ActiveProfiles("test")} 即可禁用本类(详见 application-test.yml)。
 */
@Component
@Profile("!test")
public class MarvisCliRunner implements CommandLineRunner {

    private final AgentLoopHarness harness;

    public MarvisCliRunner(AgentLoopHarness harness) {
        this.harness = harness;
    }

    @Override
    public void run(String... args) {
        harness.repl();
    }
}
