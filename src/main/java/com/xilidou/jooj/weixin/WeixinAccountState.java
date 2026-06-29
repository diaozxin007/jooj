package com.xilidou.jooj.weixin;

import com.xilidou.jooj.bootstrap.JoojHome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Weixin 账号**状态**(s21 Demo 16.5)。
 *
 * <h3>跟 {@link WeixinProperties} 的边界</h3>
 *
 * <ul>
 *   <li>{@link WeixinProperties} = **配置**(用户想让 jooj 怎么跑):enabled / botAgent / poolSize 等。
 *       从 application.yml 读,跨重启依赖 yml 文件本身。</li>
 *   <li>{@link WeixinAccountState} = **状态**(jooj 实际跑成什么样):当前 active accountId。
 *       从 {@code ~/.jooj/weixin/state.json} 读写,**跟 yml 完全无关**。</li>
 * </ul>
 *
 * <p>这次拆分修了之前把 accountId 塞进 yml 的设计错配 —— accountId 是腾讯 server 扫码时随机
 * 生成的 hex(用户事先不可能知道),写 yml 没意义,本质是状态。
 *
 * <h3>当前简化:只记 1 个 active</h3>
 *
 * <p>多账号场景目前不需要。state.json 字段就一个 {@code activeAccountId}。SDK 内部 accountStore
 * 仍能列出所有 token 文件,但 jooj 这一侧只看 active。
 */
@Component
@ConditionalOnProperty(prefix = "jooj.weixin", name = "enabled", havingValue = "true")
@Slf4j
public class WeixinAccountState {

    /** 状态文件路径 = {@code ~/.jooj/weixin/state.json}。 */
    public static Path stateFile() {
        return JoojHome.getHomePath().resolve("weixin").resolve("state.json");
    }

    /**
     * 当前 active accountId。null 表示从未扫码登录过。
     *
     * <p>调用方:WeixinChannel / WeixinController / WeixinTool 在所有 SDK 调用前用这个值。
     */
    public synchronized String getActiveAccountId() {
        Path file = stateFile();
        if (!Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file).trim();
            if (content.isBlank()) return null;
            return parseActive(content);
        } catch (IOException e) {
            log.warn("[Weixin] failed to read state.json: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 设置 active accountId 并落盘。{@link WeixinChannel#restartWithAccount} 在切 account 时调。
     *
     * <p>SDK 内部 accountId 有两种形式:server 给的 raw {@code xxx@im.bot},以及 canonical
     * {@code xxx-im-bot}(用作磁盘文件名)。这里**统一存 canonical 形式**,跟 SDK 的
     * {@code accounts.json} 索引保持一致,排查问题更直观。
     *
     * <p>{@link #getActiveAccountId} 返回的也是 canonical 形式,SDK {@code load(id)} 内部有
     * raw↔canonical fallback 兼容,所以传 canonical 也能直接命中文件。
     */
    public synchronized void setActiveAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            log.warn("[Weixin] setActiveAccountId called with blank value, skipping");
            return;
        }
        // raw "xxx@im.bot" → canonical "xxx-im-bot"。已经是 canonical 时是 no-op。
        String canonical = cn.langchat.openclaw.weixin.storage.AccountIdCompat.normalizeLikeTs(accountId);
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            // 极简 JSON,避免引入 jackson 依赖到 weixin 包
            Files.writeString(file, "{\"activeAccountId\":\"" + escape(canonical) + "\"}\n");
            log.info("[Weixin] active account persisted to {} -> {}", file, canonical);
        } catch (IOException e) {
            log.warn("[Weixin] failed to persist state.json: {}", e.getMessage());
        }
    }

    /** 当前是否有 active account(扫过码)。 */
    public boolean hasActiveAccount() {
        return getActiveAccountId() != null;
    }

    // ── 极简 JSON 解析(只认识 {"activeAccountId":"..."}),避免 jackson 跨包依赖 ──
    //
    // 不写 regex 是因为转义 + 失败时定位都麻烦;手卷 indexOf 链够用。
    // 单元测试 WeixinAccountStateTest 锁定行为不再退化。

    static String parseActive(String json) {
        if (json == null) return null;
        // 容错:看是不是裸字符串(非 JSON 开头,直接返回 trim)
        if (!json.startsWith("{")) return json.trim();

        // 找 "activeAccountId" 这个 key
        int keyIdx = json.indexOf("\"activeAccountId\"");
        if (keyIdx < 0) return null;

        // 跳过 key 和冒号,找 value 起始引号
        int afterKey = keyIdx + "\"activeAccountId\"".length();
        int valStart = json.indexOf('"', afterKey);   // value 起始 "
        if (valStart < 0) return null;

        // 找 value 结束引号(从 valStart+1 起)
        int valEnd = json.indexOf('"', valStart + 1);
        if (valEnd < 0) return null;

        return json.substring(valStart + 1, valEnd);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
