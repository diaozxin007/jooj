package com.xilidou.jooj.weixin;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.model.WeixinMessage;
import cn.langchat.openclaw.weixin.monitor.WeixinLongPollMonitor;
import com.xilidou.jooj.channel.ChannelMessage;
import com.xilidou.jooj.channel.InboundDispatcher;
import com.xilidou.jooj.channel.MessageChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WeixinChannel —— Weixin 入站消息渠道实现。
 *
 * <h3>职责</h3>
 *
 * <ul>
 *   <li>启动 SDK 长轮询监听器(后台 daemon 线程)</li>
 *   <li>每条 inbound message 转 {@link ChannelMessage} 派给 {@link InboundDispatcher}</li>
 *   <li>{@link #sendOutbound} 调 SDK sendText 把 LLM 回复发回去</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 *
 * <ul>
 *   <li>1 个 daemon 线程跑 SDK 的 {@code monitor.runLoop()} 长轮询</li>
 *   <li>消息进来后,**派工到 worker pool**(s21:bounded pool, 默认 4 线程)再调 dispatcher;
 *       不在长轮询线程里直接调 LLM,否则 LLM 慢就会卡下一次 getUpdates,
 *       SDK server-suggested timeout 会过期,微信会断流</li>
 *   <li>不同 peer 走不同 worker 真并发跑 LLM;同 peer 仍 session lock 串行(防 history 写竞争)</li>
 * </ul>
 *
 * <h3>登录依赖</h3>
 *
 * <p>未登录(没扫码)→ {@link #start} 直接早退、不起监听 +
 * 大字 warn。用户先走 REST {@code /api/weixin/qr/start} 扫码,然后重启 jooj 让 channel 起来。
 * 这是 YAGNI 选择 —— 不在 channel 里再做一次 QR 状态轮询。
 */
@Component
@ConditionalOnProperty(prefix = "jooj.weixin", name = "enabled", havingValue = "true")
@Slf4j
public class WeixinChannel implements MessageChannel {

    public static final String NAME = "weixin";

    private final OpenClawWeixinSdk sdk;
    private final WeixinProperties props;
    private final WeixinAccountState accountState;
    private final InboundDispatcher dispatcher;

    private volatile WeixinLongPollMonitor monitor;
    private Thread pollThread;
    private ExecutorService dispatchExecutor;
    private volatile boolean running = false;

    public WeixinChannel(OpenClawWeixinSdk sdk,
                         WeixinProperties props,
                         WeixinAccountState accountState,
                         InboundDispatcher dispatcher) {
        this.sdk = sdk;
        this.props = props;
        this.accountState = accountState;
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @PostConstruct
    public void onStartup() {
        // jooj.weixin.enabled=true 时自动启动 channel(避免再额外加 enabled 开关)
        start(dispatcher);
    }

    @Override
    public synchronized void start(InboundDispatcher dispatcher) {
        if (running) {
            log.info("[Channel:weixin] already running, skipping start");
            return;
        }
        String acc = accountState.getActiveAccountId();
        if (sdk.accounts().load(acc).isEmpty()) {
            log.warn("[Channel:weixin] account '{}' not logged in, channel disabled. " +
                    "Run POST /api/weixin/qr/start, scan, then restart jooj.", acc);
            return;
        }
        dispatcher.registerChannel(this);

        // s21 改造:dispatch worker 从单线程池升级为 bounded pool。
        //
        // 旧版(Demo 11):newSingleThreadExecutor → 不同 peer 的消息串行,alice 的 LLM
        //                  慢 30s,bob 真的等 30s 才被处理(实测 SessionConcurrencyStressTest
        //                  poolSize=1 → 6056ms;poolSize=2 → 3019ms,100% 提速)。
        // 新版:bounded pool by props.dispatchPoolSize(默认 4)→ 4 个 peer 真并发。
        //
        // 用 bounded 而非 cached:防止极端情况(瞬间 100 个 peer 一起来)起 100 线程把
        // jooj 占满 + 同时把 Anthropic 配额打爆。
        int poolSize = Math.max(1, props.getDispatchPoolSize());
        dispatchExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "weixin-dispatch");
            t.setDaemon(true);
            return t;
        });

        // s21 Demo 16.8:apiClient 设动态 token supplier —— 每次 HTTP 请求实时从 accountStore
        // 读 active account 的 token,塞进 Authorization 头。
        // 原 SDK 设计 token 写在 WeixinClientConfig 里(SDK 构造时定死),不适合 jooj 这种
        // "启动时还没扫码 → 扫码后才有 token" 流程。修法见 WeixinApiClient.setTokenSupplier。
        sdk.api().setTokenSupplier(() -> {
            String activeId = accountState.getActiveAccountId();
            if (activeId == null || activeId.isBlank()) return null;
            return sdk.accounts().load(activeId)
                    .map(a -> a.token())
                    .orElse(null);
        });

        // SDK long-poll monitor —— handler 把消息转交给 worker pool
        monitor = sdk.createMonitor(acc, this::onMessage);
        pollThread = new Thread(() -> {
            try {
                monitor.runLoop();
            } catch (Throwable t) {
                log.error("[Channel:weixin] long-poll loop died", t);
            } finally {
                running = false;
            }
        }, "weixin-longpoll");
        pollThread.setDaemon(true);
        pollThread.start();
        running = true;
        log.info("[Channel:weixin] started (account={})", acc);
    }

    /** SDK 长轮询线程回调 —— 不在这里直接走 LLM,丢给 dispatch worker。 */
    private void onMessage(WeixinMessage msg) {
        // 自己发的消息(echo)→ 跳过。toUserId 是当前账号、fromUserId 跟 toUserId 相同 → 自己回的。
        // 这里粗略过滤:fromUserId 为空(SDK 无法区分)的也跳过。
        if (msg.fromUserId() == null || msg.fromUserId().isBlank()) {
            log.debug("[Channel:weixin] inbound without fromUserId, skipping");
            return;
        }
        String text = msg.textBody();
        if (text == null || text.isBlank()) {
            // 媒体消息暂不处理 —— Demo 11 范围只覆盖文本闭环
            log.info("[Channel:weixin] inbound non-text from {}, skipping (text-only for now)",
                    msg.fromUserId());
            return;
        }
        ChannelMessage cm = new ChannelMessage(
                NAME,
                msg.fromUserId(),
                null,                                  // peerName SDK 没暴露,留空
                text,
                msg.messageId() != null ? msg.messageId().toString() : null
        );
        // 进队列即返回,长轮询线程立刻继续 getUpdates
        dispatchExecutor.submit(() -> {
            try {
                dispatcher.dispatch(cm);
            } catch (Throwable t) {
                log.error("[Channel:weixin] dispatch error from peer={}", cm.peerId(), t);
            }
        });
    }

    @Override
    public void sendOutbound(String peerId, String text) {
        String acc = accountState.getActiveAccountId();
        sdk.sendText(acc, peerId, text);
    }

    /**
     * Demo 15.8:扫码登录后切换到新 accountId 不重启 jooj。
     *
     * <p>触发方:{@link WeixinController#waitQr} 拿到新 accountId 后调一次。
     * 行为:停掉旧 monitor → 改 props.defaultAccountId 到新 id → 重启 monitor。
     *
     * <p>不持久化到 application.yml(运行时修改)。jooj 重启后会读 yml 默认值。
     * 想永久切换,用户自己改 yml。
     */
    public synchronized void restartWithAccount(String newAccountId) {
        if (newAccountId == null || newAccountId.isBlank()) {
            log.warn("[Channel:weixin] restartWithAccount called with blank id, skipping");
            return;
        }
        long t0 = System.currentTimeMillis();
        log.info("[Channel:weixin] restarting with new account {} (was {})",
                newAccountId, accountState.getActiveAccountId());

        // s21 Demo 16.7:腾讯 server 端扫码后 session 几秒内必须开始 long-poll,
        // 否则被 GC 掉变 errcode=-14。要把 hot-restart 总耗时压到最小。
        //
        // **策略**:不等旧 monitor 真正退出 —— stop() 标记 atomic,然后**立刻**起新 monitor。
        // 旧 monitor 在自己线程里捕获 stop 标记会自然退出(几秒后),期间两个 monitor 并存
        // 无所谓 —— SDK 内部按 accountId 隔离,不冲突。
        stop();
        accountState.setActiveAccountId(newAccountId);
        start(this.dispatcher);
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[Channel:weixin] hot-restart took {}ms — if > 5000ms 腾讯 session 可能已 GC", elapsed);
    }

    @Override
    @PreDestroy
    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (monitor != null) monitor.stop();
        } catch (Exception e) {
            log.warn("[Channel:weixin] monitor.stop failed: {}", e.getMessage());
        }
        if (dispatchExecutor != null) {
            dispatchExecutor.shutdown();
        }
        // pollThread 不强 interrupt,monitor.stop() 标记 atomic 后下一次 getUpdates 返回会自然退出
        dispatcher.unregisterChannel(NAME);
        log.info("[Channel:weixin] stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
