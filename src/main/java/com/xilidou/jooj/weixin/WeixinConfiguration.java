package com.xilidou.jooj.weixin;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.api.WeixinClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Weixin SDK Spring 装配。
 *
 * <p>{@code jooj.weixin.enabled=true} 时才装载;否则整个包 0 bean,跟没引入一样。
 *
 * <h3>SDK 来源</h3>
 *
 * <p>原本依赖 Maven Central 上的 {@code cn.langchat.openclaw:openclaw-weixin-sdk:0.1.1}。
 * s21 Demo 16 把 SDK 源码 fork 进 {@code src/main/java/cn/langchat/openclaw/weixin/}
 * (从 Maven Central 的 {@code -sources.jar} 拿真源码),修了 1 处 immutable list bug
 * (FileAccountStore.canonicalizeAccountIds 返回值)。详见笔记 [[jooj_改造日志_s21]] Demo 16。
 *
 * <h3>SDK 状态机</h3>
 *
 * <p>SDK 不需要 application.yml 里给 token —— token 来自扫码后的 botToken,
 * SDK 内部 FileAccountStore 落盘到 {@code stateDir/accounts.json}。
 * 没扫过码的全新装载:SDK 实例可用,但调发消息 API 会因为 accountStore 里没账号失败,
 * 必须先走 {@link com.xilidou.jooj.weixin.WeixinController#startQr} → 扫码 → {@code waitConfirm}。
 */
@Configuration
@ConditionalOnProperty(prefix = "jooj.weixin", name = "enabled", havingValue = "true")
@Slf4j
public class WeixinConfiguration {

    @Bean
    public OpenClawWeixinSdk weixinSdk(WeixinProperties props) {
        WeixinClientConfig config = WeixinClientConfig.builder()
                .token("")          // QR 阶段 token 留空;扫码后 SDK 自管
                .build();
        OpenClawWeixinSdk sdk = new OpenClawWeixinSdk(config);
        log.info("[Weixin] SDK initialized (botAgent={}, stateDir from env OPENCLAW_STATE_DIR or ~/.openclaw, " +
                "active account in ~/.jooj/weixin/state.json)",
                props.getBotAgent());
        return sdk;
    }
}
