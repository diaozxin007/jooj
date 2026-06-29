package com.xilidou.jooj.weixin;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.auth.QrLoginClient;
import cn.langchat.openclaw.weixin.auth.QrLoginFlowResult;
import cn.langchat.openclaw.weixin.auth.QrLoginSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Weixin 扫码登录 + 状态查询 REST 端点。
 *
 * <h3>典型使用流程</h3>
 *
 * <ol>
 *   <li>{@code POST /api/weixin/qr/start} — 拿 qrcodeUrl,用户用 Weixin 扫</li>
 *   <li>{@code POST /api/weixin/qr/wait} — 长轮询(默认 2 分钟超时)等用户确认</li>
 *   <li>(成功后)账号自动落地到 SDK stateDir,LLM 工具 weixin_send_text 可用</li>
 * </ol>
 *
 * <h3>SSE / WebSocket?</h3>
 *
 * <p>暂不做。普通 long-poll(等扫码确认 < 2min)够用,前端 UI 起一个 fetch 等返回即可,
 * 复杂度更低。
 */
@RestController
@RequestMapping("/api/weixin")
@ConditionalOnProperty(prefix = "jooj.weixin", name = "enabled", havingValue = "true")
@Slf4j
public class WeixinController {

    private final OpenClawWeixinSdk sdk;
    private final WeixinProperties props;
    private final WeixinAccountState accountState;
    private final WeixinChannel channel;

    public WeixinController(OpenClawWeixinSdk sdk, WeixinProperties props,
                            WeixinAccountState accountState, WeixinChannel channel) {
        this.sdk = sdk;
        this.props = props;
        this.accountState = accountState;
        this.channel = channel;
    }

    /** SDK qrFlow().start/waitForConfirm 的 sessionKey —— 跟 accountId 无关的固定字符串,
     *  用于让 wait 能找回 start 创建的 session(SDK 内部 sessions map 的 key)。 */
    private static final String QR_SESSION_KEY = "jooj-qr";

    /**
     * 开始扫码登录 —— 返回二维码 URL。前端拿 qrcodeUrl 让用户用 Weixin 扫。
     *
     * <p>同一个 sessionKey 重复调:SDK 会重置 session,旧 QR 失效,生成新 QR。
     */
    @PostMapping("/qr/start")
    public ResponseEntity<Map<String, Object>> startQr() {
        // 第 2 个参数是 SDK 内部的 bot_type(透传到腾讯 server 的 ?bot_type=X 查询参数),
        // 默认 "3"(QrLoginClient.DEFAULT_BOT_TYPE)。**这不是 botAgent**——botAgent 是
        // 我们的标识透传给 server 审计,bot_type 是腾讯定义的客户端类型枚举。
        QrLoginSession session = sdk.qrFlow().start(QR_SESSION_KEY, QrLoginClient.DEFAULT_BOT_TYPE, true);
        log.info("[Weixin] QR session started: qr={}", session.qrcode());
        return ResponseEntity.ok(Map.of(
                "qrcode", session.qrcode(),
                "qrcodeUrl", session.qrcodeUrl(),
                "startedAtMs", session.startedAtMs()
        ));
    }

    /**
     * 等扫码确认 —— 长轮询。默认 2 min 超时;到时未确认返回 {@code connected=false}。
     *
     * <p>s21 Demo 16:SDK fork 修复 immutable list bug 后,这里不再需要 catch UoE。
     * hot-restart channel 仍保留 —— 腾讯 server 端 session 几分钟不 long-poll 就 GC。
     */
    @PostMapping("/qr/wait")
    public ResponseEntity<Map<String, Object>> waitQr() {
        // 第 3 个参数同样是 bot_type(二维码过期续期时复用),不是 botAgent。
        QrLoginFlowResult r = sdk.qrFlow().waitForConfirm(
                QR_SESSION_KEY, Duration.ofMinutes(2), QrLoginClient.DEFAULT_BOT_TYPE);

        if (r.connected() && r.accountId() != null && !r.accountId().isBlank()) {
            // SDK 返回的 accountId 是腾讯端生成的随机 hex id(如 13deff2a283e-im-bot)。
            // 立刻 hot-restart channel 切到新 id,维持腾讯 server 端 session
            // (几分钟不 long-poll 会被 GC),并把新 id 持久化到 ~/.jooj/weixin/state.json。
            try {
                channel.restartWithAccount(r.accountId());
                log.info("[Weixin] hot-restarted channel with new account {}", r.accountId());
            } catch (Exception e) {
                log.warn("[Weixin] failed to hot-restart channel: {}", e.getMessage());
            }
        }

        log.info("[Weixin] QR result: connected={} accountId={} userId={}",
                r.connected(), r.accountId(), r.userId());
        return ResponseEntity.ok(Map.of(
                "accountId", r.accountId() == null ? "" : r.accountId(),
                "connected", r.connected(),
                "userId", r.userId() == null ? "" : r.userId(),
                "message", r.message() == null ? "" : r.message()
        ));
    }

    /** 当前 default account 的登录状态 + channel 在跑没。 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        String acc = accountState.getActiveAccountId();
        boolean channelRunning = channel.isRunning();
        if (acc == null) {
            return ResponseEntity.ok(Map.of(
                    "loggedIn", false,
                    "accountId", "",
                    "channelRunning", channelRunning,
                    "hint", "No active account. Scan QR via POST /api/weixin/qr/start"
            ));
        }
        return sdk.accounts().load(acc)
                .<ResponseEntity<Map<String, Object>>>map(a -> ResponseEntity.ok(Map.of(
                        "loggedIn", true,
                        "accountId", a.accountId(),
                        "userId", a.userId() == null ? "" : a.userId(),
                        "savedAt", a.savedAt() == null ? "" : a.savedAt(),
                        "channelRunning", channelRunning
                )))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "loggedIn", false,
                        "accountId", acc,
                        "channelRunning", channelRunning,
                        "hint", "active account in state.json but token file missing — re-scan QR"
                )));
    }
}
