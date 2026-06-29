package com.xilidou.jooj.weixin;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.auth.QrLoginClient;
import cn.langchat.openclaw.weixin.auth.QrLoginFlowResult;
import cn.langchat.openclaw.weixin.auth.QrLoginSession;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

/**
 * 手动 smoke 测试 —— 验证整个 Weixin 链路真的能跟腾讯 server 跑通。
 *
 * <h3>用法</h3>
 *
 * <ol>
 *   <li>取消下面 {@code @Disabled},或在 IDE 里直接 run 单个方法(IDE 通常会无视 @Disabled)</li>
 *   <li>跑 {@link #smoke_qr_login_and_send_text} —— 控制台会打印 qrcodeUrl</li>
 *   <li>用 Weixin 扫这个 URL,在手机上确认登录</li>
 *   <li>测试会在 2 分钟超时内等到确认,然后自动给 filehelper 发一条测试消息</li>
 *   <li>跑完检查你的 Weixin filehelper 收没收到</li>
 * </ol>
 *
 * <h3>为什么默认 disable?</h3>
 *
 * <ul>
 *   <li>需要真实网络访问腾讯 server</li>
 *   <li>需要人肉扫码</li>
 *   <li>会在 ~/.openclaw 落账号文件,跟 CI 不共享</li>
 *   <li>跑过一次后 SDK 记住账号,下次起 jooj 直接可发消息(jooj.weixin.enabled=true)</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "jooj.weixin.enabled=true",
        "jooj.weixin.default-account-id=smoke-test",
        "jooj.weixin.bot-agent=jooj-smoke"
})
@Disabled("Manual only: requires real network + QR scan. Remove @Disabled and run from IDE when needed.")
class WeixinSmokeManual {

    @Autowired OpenClawWeixinSdk sdk;
    @Autowired WeixinProperties props;
    @Autowired WeixinAccountState accountState;

    @Test
    @DisplayName("Manual: QR login → send text to filehelper")
    void smoke_qr_login_and_send_text() {
        // s21 Demo 16.5: accountId 从 state.json 读
        String acc = accountState.getActiveAccountId();

        // 已登录(state.json 有 + token 文件存在)就跳过 QR
        if (acc != null && sdk.accounts().load(acc).isPresent()) {
            System.out.println("[smoke] account '" + acc + "' already logged in, skipping QR");
        } else {
            QrLoginSession session = sdk.qrFlow().start("jooj-smoke", QrLoginClient.DEFAULT_BOT_TYPE, true);
            System.out.println("\n========== SCAN THIS QR ==========");
            System.out.println("URL : " + session.qrcodeUrl());
            System.out.println("code: " + session.qrcode());
            System.out.println("=================================\n");
            System.out.println("[smoke] waiting up to 2min for confirm...");

            QrLoginFlowResult r = sdk.qrFlow().waitForConfirm(
                    "jooj-smoke", Duration.ofMinutes(2), QrLoginClient.DEFAULT_BOT_TYPE);
            if (!r.connected()) {
                throw new AssertionError("QR confirm timeout/failed: " + r.message());
            }
            // SDK 给的新 accountId 落 state.json
            acc = r.accountId();
            accountState.setActiveAccountId(acc);
            System.out.println("[smoke] connected as account=" + acc + " userId=" + r.userId());
        }

        // 给 filehelper 发一条
        String msgId = sdk.sendText(acc, "filehelper", "hello from jooj smoke test");
        System.out.println("[smoke] sent msgId=" + msgId);
    }
}
