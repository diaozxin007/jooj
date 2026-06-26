package com.xilidou.jooj.cron;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link CronExpression} 的解析 + 匹配语义。
 *
 * <p>覆盖:
 * <ul>
 *   <li>5 字段解析 + 单值 / * / *​/N / a-b / a,b,c</li>
 *   <li>DOM/DOW OR 语义(踩过的坑)</li>
 *   <li>非法表达式返回错误字符串</li>
 *   <li>跨天 marker 防误判</li>
 * </ul>
 */
class CronExpressionTest {

    @Test
    @DisplayName("'0 9 * * *' 在 9:00 match,9:01/8:59 不 match")
    void minute_hour_match() {
        // 2026-06-25 是周四(Thursday) — DOW = 4 (cron 周四)
        assertTrue(CronExpression.matches("0 9 * * *",
                LocalDateTime.of(2026, 6, 25, 9, 0)));
        assertFalse(CronExpression.matches("0 9 * * *",
                LocalDateTime.of(2026, 6, 25, 9, 1)));
        assertFalse(CronExpression.matches("0 9 * * *",
                LocalDateTime.of(2026, 6, 25, 8, 59)));
    }

    @Test
    @DisplayName("'*​/5 * * * *' 在 0/5/10 分钟 match,在 3 分钟不 match")
    void step_match() {
        assertTrue(CronExpression.matches("*/5 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 0)));
        assertTrue(CronExpression.matches("*/5 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 5)));
        assertTrue(CronExpression.matches("*/5 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 10)));
        assertFalse(CronExpression.matches("*/5 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 3)));
    }

    @Test
    @DisplayName("'0 9 * * 1-5' 周五 9 点 match,周六不")
    void range_dow_match() {
        // 2026-06-26 是周五,DOW=5;2026-06-27 周六,DOW=6
        assertTrue(CronExpression.matches("0 9 * * 1-5",
                LocalDateTime.of(2026, 6, 26, 9, 0)));
        assertFalse(CronExpression.matches("0 9 * * 1-5",
                LocalDateTime.of(2026, 6, 27, 9, 0)),
                "周六不 match");
    }

    @Test
    @DisplayName("DOM/DOW OR:'0 9 1 * 1' 周一 9 点 match;月 1 号 9 点也 match")
    void dom_dow_or_semantic() {
        // 2026-06-22 周一,DOM=22 → DOM 不命中(只匹配 1),但 DOW=1 命中 → OR 应过
        assertTrue(CronExpression.matches("0 9 1 * 1",
                LocalDateTime.of(2026, 6, 22, 9, 0)),
                "周一(DOW=1)9 点应该 match,即使不是月 1 号(OR 语义)");
        // 2026-09-01 周二,DOM=1 命中,DOW=2 不命中 → OR 应过
        assertTrue(CronExpression.matches("0 9 1 * 1",
                LocalDateTime.of(2026, 9, 1, 9, 0)),
                "9 月 1 号(DOM=1)9 点应该 match,即使不是周一(OR 语义)");
        // 周二非 1 号都不该 match —— DOM=2 不命中,DOW=2 不命中
        assertFalse(CronExpression.matches("0 9 1 * 1",
                LocalDateTime.of(2026, 6, 23, 9, 0)),
                "DOM/DOW 都不命中应不 match");
    }

    @Test
    @DisplayName("DOW * 时只看 DOM:'0 9 15 * *' 任意星期的 15 号 9 点 match")
    void dow_star_only_dom() {
        // 2026-06-15 周一
        assertTrue(CronExpression.matches("0 9 15 * *",
                LocalDateTime.of(2026, 6, 15, 9, 0)));
        // 2026-06-14 周日 14 号 不 match
        assertFalse(CronExpression.matches("0 9 15 * *",
                LocalDateTime.of(2026, 6, 14, 9, 0)));
    }

    @Test
    @DisplayName("DOM * 时只看 DOW:'0 9 * * 0' 任意周日 9 点 match")
    void dom_star_only_dow() {
        // 2026-06-28 周日,Java DOW=7,cron DOW=0
        assertTrue(CronExpression.matches("0 9 * * 0",
                LocalDateTime.of(2026, 6, 28, 9, 0)),
                "周日 cron DOW=0 应 match");
        assertFalse(CronExpression.matches("0 9 * * 0",
                LocalDateTime.of(2026, 6, 29, 9, 0)),
                "周一不 match");
    }

    @Test
    @DisplayName("两个 DOM/DOW 都 *:'0 9 * * *' 任意日期 9 点 match")
    void both_star() {
        assertTrue(CronExpression.matches("0 9 * * *",
                LocalDateTime.of(2026, 6, 25, 9, 0)));
        assertTrue(CronExpression.matches("0 9 * * *",
                LocalDateTime.of(2026, 12, 31, 9, 0)));
    }

    @Test
    @DisplayName("列表 a,b,c:'0 9,12,18 * * *' 三个时间点 match")
    void list_match() {
        assertTrue(CronExpression.matches("0 9,12,18 * * *",
                LocalDateTime.of(2026, 6, 25, 9, 0)));
        assertTrue(CronExpression.matches("0 9,12,18 * * *",
                LocalDateTime.of(2026, 6, 25, 12, 0)));
        assertTrue(CronExpression.matches("0 9,12,18 * * *",
                LocalDateTime.of(2026, 6, 25, 18, 0)));
        assertFalse(CronExpression.matches("0 9,12,18 * * *",
                LocalDateTime.of(2026, 6, 25, 15, 0)));
    }

    @Test
    @DisplayName("非法表达式返回错误字符串")
    void invalid_expressions_return_error() {
        // 60 分钟超出 0-59
        assertNotNull(CronExpression.validate("60 * * * *"));
        // 6 字段
        assertNotNull(CronExpression.validate("0 9 * * * *"));
        // 范围超界
        assertNotNull(CronExpression.validate("1-99 * * * *"));
        // 字段非数字
        assertNotNull(CronExpression.validate("abc * * * *"));
        // 5 字段空
        assertNotNull(CronExpression.validate("    "));
    }

    @Test
    @DisplayName("合法表达式 validate 返回 null")
    void valid_expressions_return_null() {
        assertNull(CronExpression.validate("0 9 * * *"));
        assertNull(CronExpression.validate("*/5 * * * *"));
        assertNull(CronExpression.validate("0 0 1 1 0"));
        assertNull(CronExpression.validate("0,15,30,45 * * * *"));
        assertNull(CronExpression.validate("0 9-17 * * 1-5"));
    }

    @Test
    @DisplayName("跨分钟 / 跨天:同一 marker 一个时刻不应同时 match 两个非互斥分钟")
    void minute_boundary() {
        // 23:59 vs 00:00 是不同分钟,marker 不同
        // (这个测试本质是 string format,放在 CronServiceTest 更合适;这里只验 expression match 本身)
        assertTrue(CronExpression.matches("59 23 * * *",
                LocalDateTime.of(2026, 6, 25, 23, 59)));
        assertFalse(CronExpression.matches("59 23 * * *",
                LocalDateTime.of(2026, 6, 26, 0, 0)));
    }

    @Test
    @DisplayName("步长结合范围:'0-30/10 * * * *' 在 0/10/20/30 match")
    void step_with_range() {
        assertTrue(CronExpression.matches("0-30/10 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 0)));
        assertTrue(CronExpression.matches("0-30/10 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 10)));
        assertTrue(CronExpression.matches("0-30/10 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 30)));
        assertFalse(CronExpression.matches("0-30/10 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 5)));
        assertFalse(CronExpression.matches("0-30/10 * * * *",
                LocalDateTime.of(2026, 6, 25, 12, 40)));
    }
}
