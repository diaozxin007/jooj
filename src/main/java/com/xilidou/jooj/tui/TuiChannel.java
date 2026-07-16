package com.xilidou.jooj.tui;

import com.xilidou.jooj.channel.InboundDispatcher;
import com.xilidou.jooj.channel.MessageChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * TuiChannel —— TUI 入站渠道,实现 {@link MessageChannel} 契约。
 *
 * <h3>与 WeixinChannel 的差异</h3>
 *
 * <p>虽然实现同一个接口,但 TUI 在语义上跟 IM channel(weixin / discord / ...)有本质区别:
 *
 * <table>
 *   <tr><th>维度</th><th>WeixinChannel</th><th>TuiChannel</th></tr>
 *   <tr><td>入站源</td><td>长轮询 IM 服务器</td><td>本地 stdin</td></tr>
 *   <tr><td>并发 peer</td><td>可能几十个 peer 同时来</td><td>永远 1 个(本地用户)</td></tr>
 *   <tr><td>调用模式</td><td>{@code dispatch} fire-and-forget</td><td>{@code dispatchSync} 同步返 reply</td></tr>
 *   <tr><td>出站</td><td>{@code sendOutbound} 调 SDK 发消息</td><td>不适用(reply 通过 event 打屏)</td></tr>
 *   <tr><td>Session 路由</td><td>{@code chat_weixin_<peerId>}</td><td>{@code Session.CLI_DEFAULT_ID}</td></tr>
 * </table>
 *
 * <h3>P2 阶段边界</h3>
 *
 * <p>本类目前只是**骨架** —— 只装配 bean、注册 channel、log 一句 "started"。真正的
 * read loop + dispatchSync 集成在 P3 追加。原因:先让 spring context 起得来 + JLine terminal
 * 能建成 + mvn test 通过,再迭代 loop 逻辑。
 *
 * <h3>Session 策略</h3>
 *
 * <p>TUI 复用 {@link com.xilidou.jooj.session.Session#CLI_DEFAULT_ID} —— 跟 legacy CLI 共享 session
 * (对话历史 / todo / memory 全部继承)。用户从 legacy CLI 切到 TUI 不丢历史,反之亦然。
 * 未来 P9 侧栏支持切多 session 时,session id 走用户选择。
 */
@Component
@Profile("tui")
public class TuiChannel implements MessageChannel {

    private static final Logger log = LoggerFactory.getLogger(TuiChannel.class);
    public static final String NAME = "tui";

    private final TuiTerminal tui;
    private final InboundDispatcher dispatcher;
    private volatile boolean running = false;
    private Thread readerThread;

    public TuiChannel(TuiTerminal tui, InboundDispatcher dispatcher) {
        this.tui = tui;
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Spring 装配完成后自动启动(与 WeixinChannel 对齐)。
     *
     * <p>{@code jooj.tui.enabled} 开关暂不做 —— {@code @Profile("tui")} 已经门控整个域,
     * 二级开关是过度设计。想禁用直接不加 profile。
     */
    @PostConstruct
    public void onStartup() {
        start(dispatcher);
    }

    @Override
    public synchronized void start(InboundDispatcher dispatcher) {
        if (running) {
            log.info("[TUI] channel already running, skipping start");
            return;
        }
        dispatcher.registerChannel(this);
        running = true;

        // P3 会替换成真正的 read loop:
        //   - 起 daemon 线程读 stdin
        //   - 每行 dispatchSync → 用 TurnEventPushed / AssistantResponseCompleted 事件打屏
        // P2 只 log 一句证明装配路径通(mvn test 里 @SpringBootTest 装 tui profile 不会挂)
        log.info("[TUI] channel started (P2 skeleton — read loop lands in P3)");
    }

    @Override
    @PreDestroy
    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        dispatcher.unregisterChannel(NAME);
        log.info("[TUI] channel stopped");
    }

    /**
     * MessageChannel 契约要求实现,但 TUI 语义下**不该被调用**。
     *
     * <p>TUI 只服务本地用户,agent 想主动 outbound 到"peer"是不合逻辑的(peer 就是当前
     * 敲键盘的人,通过普通 assistant reply / SSE 事件就能看到)。若真被调用了(比如 lead
     * 通过 team message bus 尝试给 tui channel 发 outbound),打 warn 而不静默丢。
     */
    @Override
    public void sendOutbound(String peerId, String text) {
        log.warn("[TUI] sendOutbound invoked (peerId={}, text_len={}) — TUI channel does not "
                + "accept remote outbound. Ignoring.", peerId, text == null ? 0 : text.length());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // 供 P3 read loop 用的辅助方法,骨架阶段先不放代码。
    // Ctrl-C → LineReader.readLine 抛 UserInterruptException
    // Ctrl-D / EOF → LineReader.readLine 抛 EndOfFileException
    @SuppressWarnings("unused")
    private void placeholderForP3Signals() {
        // P3 会真用这两个 exception 类型;这里只是 preventive import (防止 IDE 优化 imports 误删)
        Class<?> a = UserInterruptException.class;
        Class<?> b = EndOfFileException.class;
    }
}
