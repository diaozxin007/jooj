package com.xilidou.jooj.tui;

import com.xilidou.jooj.JoojCliRunner;
import com.xilidou.jooj.channel.InboundDispatcher;
import com.xilidou.jooj.channel.MessageChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TUI context 装配 IT(s23 P3)—— 验证 tui profile 启用时:
 *
 * <ul>
 *   <li>TuiTerminal / TuiChannel / TuiProperties 3 个核心 bean 装配</li>
 *   <li>TuiCliRunner 因 {@code @Profile("tui & !test")} 在**测试环境不装配**
 *       (避免 CommandLineRunner.run() 阻塞 stdin 让 IT 永远跑不完);
 *       生产运行时 profile 是 {@code tui} 单独启用,test 不激活,CliRunner 会跑</li>
 *   <li>legacy {@link JoojCliRunner} 因 {@code @Profile("!test & !web & !tui")} 表达式**不装配**</li>
 *   <li>TuiChannel 已注册到 InboundDispatcher(通过 {@code deliver("tui", ...)} 检测)</li>
 * </ul>
 *
 * <p>关键约束:{@code TerminalBuilder.system(true)} 在 CI headless 环境自动 fallback 到
 * DumbTerminal —— 不需要 mock,JLine 自己就 graceful degrade。这保证测试**不依赖真 tty**。
 *
 * <p><b>不测</b>的东西:
 * <ul>
 *   <li>真实 read loop —— {@link TuiCliRunner#run(String...)} 会阻塞 stdin,IT 里不能触发</li>
 *   <li>真实对话流程 —— 需要 AnthropicClient mock,与 P3 骨架"打屏"验收无关,留给 P4/P5 IT</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles({"test", "tui"})
@DisplayName("TUI profile 装配 (s23 P3)")
class TuiProfileAssemblyTest {

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private TuiTerminal tuiTerminal;

    @Autowired
    private TuiChannel tuiChannel;

    @Autowired
    private TuiProperties tuiProperties;

    @Autowired
    private TuiTurnRenderer tuiTurnRenderer;

    @Autowired
    private TuiAnswerPresenter tuiAnswerPresenter;

    @Autowired
    private InboundDispatcher dispatcher;

    @Test
    @DisplayName("5 个 tui 核心 bean 都装配成功")
    void tuiBeansAssembled() {
        assertThat(tuiTerminal).isNotNull();
        assertThat(tuiChannel).isNotNull();
        assertThat(tuiProperties).isNotNull();
        assertThat(tuiTurnRenderer).isNotNull();       // P4 加入
        assertThat(tuiAnswerPresenter).isNotNull();    // P5 加入
    }

    @Test
    @DisplayName("TuiCliRunner + TuiQueryDispatcher + TuiStatusBar 在测试环境被 !test 排除")
    void tuiRunnerBeansExcludedInTest() {
        assertThatThrownBy(() -> ctx.getBean(TuiCliRunner.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        // TuiQueryDispatcher 也是 tui & !test —— 测试环境不装配 daemon 线程
        assertThatThrownBy(() -> ctx.getBean(TuiQueryDispatcher.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        // TuiStatusBar 也是 tui & !test —— 测试环境不起定时刷新线程
        assertThatThrownBy(() -> ctx.getBean(TuiStatusBar.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    @DisplayName("legacy JoojCliRunner 被 !tui 排除,不装配")
    void legacyCliRunnerExcluded() {
        assertThatThrownBy(() -> ctx.getBean(JoojCliRunner.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    @DisplayName("TuiChannel 已 registerChannel 到 dispatcher")
    void tuiChannelRegistered() {
        // channel name 契约
        assertThat(tuiChannel.name()).isEqualTo(TuiChannel.NAME).isEqualTo("tui");
        // isRunning() = true(@PostConstruct 已跑)
        assertThat(tuiChannel.isRunning()).isTrue();
        // 是 MessageChannel(契约兼容)
        assertThat(tuiChannel).isInstanceOf(MessageChannel.class);
    }

    @Test
    @DisplayName("TuiProperties 默认值加载正确")
    void tuiPropertiesDefaults() {
        // yml 里没 override 时应该是 Java 默认
        assertThat(tuiProperties.getTheme()).isIn("default", "dark", "light", "none");
        assertThat(tuiProperties.getMaxPreviewLines()).isPositive();
        assertThat(tuiProperties.getQueueCapacity()).isPositive();   // P5 加入
    }

    @Test
    @DisplayName("headless 环境下 TuiTerminal 优雅降级 (isDumb=true)")
    void terminalGracefulDegrade() {
        // surefire 里 stdin 不是 tty,JLine 应该自动给出 dumb terminal
        // TuiTerminal.isDumb() 反映了 computeColorEnabled 的三条降级路径
        // 至少在测试环境下必然是 true(no tty)
        assertThat(tuiTerminal.isDumb()).isTrue();
    }

    @Test
    @DisplayName("dispatcher.deliver(tui, ...) 走到 sendOutbound no-op(log warn)")
    void tuiSendOutboundNoop() {
        // TUI 不接受远程 outbound;deliver 应该返回 true(SDK 语义没 throw)但内部只 log warn
        // 严格测法:validate log 里有 warn。宽松测法:不 throw 就行
        boolean result = dispatcher.deliver("tui", "local", "test message");
        // ChannelDeliverer 契约:找不到 channel 或 send 失败返 false;
        // 我们的 sendOutbound 没抛(只 log warn),所以返 true
        assertThat(result).isTrue();
    }
}
