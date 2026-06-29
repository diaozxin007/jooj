package com.xilidou.jooj.weixin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link WeixinAccountState#parseActive} 极简 JSON 解析的行为 —— Demo 16.6 修复
 * 一个低级数错 bug:之前找 value 起始 quote 后又找一次 quote,导致永远返 null。
 *
 * <p>不能再退化了,加测试守门。
 */
class WeixinAccountStateTest {

    @Test
    @DisplayName("正常 JSON: {\"activeAccountId\":\"xxx\"} → 返 xxx")
    void parses_normal_json() {
        assertEquals("8559177764b0-im-bot",
                WeixinAccountState.parseActive("{\"activeAccountId\":\"8559177764b0-im-bot\"}"));
    }

    @Test
    @DisplayName("末尾换行 / 空格容错")
    void tolerant_to_whitespace() {
        assertEquals("a-im-bot",
                WeixinAccountState.parseActive("{\"activeAccountId\":\"a-im-bot\"}\n"));
    }

    @Test
    @DisplayName("裸字符串(老格式)直接返")
    void plain_string_fallback() {
        assertEquals("legacy-id",
                WeixinAccountState.parseActive("legacy-id"));
        assertEquals("trimmed",
                WeixinAccountState.parseActive("  trimmed  "));
    }

    @Test
    @DisplayName("空 / null 不抛")
    void null_safe() {
        assertNull(WeixinAccountState.parseActive(null));
    }

    @Test
    @DisplayName("缺 key → 返 null")
    void missing_key() {
        assertNull(WeixinAccountState.parseActive("{\"otherKey\":\"x\"}"));
    }

    @Test
    @DisplayName("多 field 时仍能定位 activeAccountId")
    void multiple_fields() {
        assertEquals("target",
                WeixinAccountState.parseActive("{\"a\":\"x\",\"activeAccountId\":\"target\",\"b\":\"y\"}"));
    }
}
