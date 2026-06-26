package com.xilidou.jooj.cron;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 5 字段 Cron 表达式解析 + 匹配 —— 严格对齐上游
 * [s14_cron_scheduler/code.py] 的 {@code _cron_field_matches} +
 * {@code cron_matches} + {@code _validate_cron_field} + {@code validate_cron}。
 *
 * <h3>5 字段语法</h3>
 *
 * <pre>
 *   minute  hour  day_of_month  month  day_of_week
 *   0-59    0-23  1-31          1-12   0-6 (0=Sunday)
 * </pre>
 *
 * <p>每个字段支持:
 * <ul>
 *   <li>单值:{@code 9}</li>
 *   <li>通配:{@code *}</li>
 *   <li>步长:{@code *​/N}(每 N 触发,如 {@code *​/5} 每 5 分钟)</li>
 *   <li>范围:{@code a-b}(如 {@code 1-5} 表示周一到周五)</li>
 *   <li>列表:{@code a,b,c}(如 {@code 0,15,30,45})</li>
 * </ul>
 *
 * <h3>DOM/DOW OR 语义(关键)</h3>
 *
 * <p>跟上游 + 标准 cron 一致:
 * <ul>
 *   <li>两个字段都受限(非 {@code *})时,DOM <b>OR</b> DOW —— 任一满足即触发
 *       (例:{@code 0 9 1 * 1} = 每月 1 号 9 点 OR 每周一 9 点)</li>
 *   <li>一个 {@code *} 一个受限时,只看那个受限的</li>
 *   <li>两个都 {@code *} 时,DOW/DOM 都不限</li>
 * </ul>
 *
 * <p><b>这是踩过的坑</b>:错误实现成 AND 会让 {@code 0 9 1 * 1} 几乎永不触发(月 1 号
 * 是周一的概率 1/7)。
 *
 * <h3>Java DayOfWeek 转 cron DOW</h3>
 *
 * <p>Java {@link java.time.LocalDateTime#getDayOfWeek()}.getValue() 是 1-7(Monday=1,Sunday=7);
 * cron 的 DOW 是 0-6(Sunday=0)。转换公式:{@code dow_cron = (dow_java % 7)}
 * (周日 java=7 → cron=0,周一-周六 java=1..6 → cron=1..6)。
 */
public final class CronExpression {

    /**
     * 解析 5 字段 cron 字符串。
     *
     * @return 5 个 BitSet 风格的 boolean[60/24/31/12/7] —— 每个 bit 表示该位置是否命中。
     *         与上游"集合 of valid integers"等价,Java 用 boolean[] 更紧凑。
     * @throws IllegalArgumentException 表达式非法
     */
    public static int[][] parse(String cron) {
        if (cron == null) throw new IllegalArgumentException("cron expression must not be null");
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "cron expression must have exactly 5 fields (m h dom mon dow), got " + parts.length);
        }
        int[][] result = new int[5][];
        result[0] = parseField(parts[0], 0, 59, "minute");
        result[1] = parseField(parts[1], 0, 23, "hour");
        result[2] = parseField(parts[2], 1, 31, "day_of_month");
        result[3] = parseField(parts[3], 1, 12, "month");
        result[4] = parseField(parts[4], 0, 6, "day_of_week");
        return result;
    }

    /**
     * 校验 cron 表达式合法性,返回 NL 错误字符串或 null(合法)。
     *
     * <p>对应上游 {@code validate_cron} —— 失败时不抛异常,返回字符串让 LLM 自我纠正。
     *
     * @return null 表示合法;非 null 是 NL 错误信息
     */
    public static String validate(String cron) {
        try {
            parse(cron);
            return null;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 当前时间是否匹配 cron 表达式。
     *
     * <p>逻辑:解析 5 字段 → 检查 minute/hour/month 全 match → 按 DOM/DOW OR 语义判断日期。
     *
     * @param cron 5 字段 cron 字符串
     * @param now  当前 LocalDateTime
     * @return true 表示触发
     */
    public static boolean matches(String cron, LocalDateTime now) {
        int[][] fields = parse(cron);
        int min = now.getMinute();
        int hour = now.getHour();
        int dom = now.getDayOfMonth();
        int mon = now.getMonthValue();
        // Java DayOfWeek: 1=Monday..7=Sunday;cron: 0=Sunday..6=Saturday
        int dowJava = now.getDayOfWeek().getValue();
        int dowCron = dowJava % 7; // Sunday(7)→0, Mon-Sat(1-6)→1-6

        if (!hit(fields[0], min)) return false;
        if (!hit(fields[1], hour)) return false;
        if (!hit(fields[3], mon)) return false;

        // DOM/DOW OR 语义 —— 跟上游 _cron_field_matches 严格一致:
        // - 原始字段中两者都非 "*" 时:OR
        // - 一个 "*" 一个非 "*" 时:只看非 "*" 的
        // - 两个都 "*" 时:DOW/DOM 都不限制(都过)
        // 我们没保留原始字符串,但 parseField("*", lo, hi) 返回 0..hi 全集,
        // 跟受限范围在最终 boolean[] 上无法区分。所以这里用一个 trick:
        // 重新解析原始 cron 的两个字段字符串再判定。
        String[] parts = cron.trim().split("\\s+");
        boolean domStar = "*".equals(parts[2]);
        boolean dowStar = "*".equals(parts[4]);

        boolean domHit = hit(fields[2], dom);
        boolean dowHit = hit(fields[4], dowCron);

        if (domStar && dowStar) return true;       // 两 *:都不限
        if (domStar) return dowHit;                  // 只 DOM *:看 DOW
        if (dowStar) return domHit;                  // 只 DOW *:看 DOM
        return domHit || dowHit;                     // 两个都受限:OR
    }

    // ─────────────────────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析单个字段。
     *
     * @return 命中位置数组(int[],存所有命中的整数值,排序去重)
     */
    static int[] parseField(String field, int lo, int hi, String name) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException(name + " field is empty");
        }

        // 列表 a,b,c —— 递归处理每段后合并
        if (field.contains(",")) {
            List<Integer> values = new ArrayList<>();
            for (String part : field.split(",")) {
                int[] sub = parseField(part.trim(), lo, hi, name);
                for (int v : sub) values.add(v);
            }
            return toSortedDedupedArray(values);
        }

        // 步长 *​/N 或 a-b/N
        if (field.contains("/")) {
            String[] sp = field.split("/", 2);
            String range = sp[0];
            int step;
            try {
                step = Integer.parseInt(sp[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " step must be integer: " + field);
            }
            if (step <= 0) {
                throw new IllegalArgumentException(name + " step must be > 0: " + field);
            }
            int rangeLo, rangeHi;
            if ("*".equals(range)) {
                rangeLo = lo;
                rangeHi = hi;
            } else if (range.contains("-")) {
                int[] r = parseRange(range, lo, hi, name);
                rangeLo = r[0];
                rangeHi = r[1];
            } else {
                int v = parseInt(range, lo, hi, name);
                rangeLo = v;
                rangeHi = hi;
            }
            List<Integer> values = new ArrayList<>();
            for (int i = rangeLo; i <= rangeHi; i += step) {
                values.add(i);
            }
            return toSortedDedupedArray(values);
        }

        // 通配 *
        if ("*".equals(field)) {
            int n = hi - lo + 1;
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = lo + i;
            return all;
        }

        // 范围 a-b
        if (field.contains("-")) {
            int[] r = parseRange(field, lo, hi, name);
            int n = r[1] - r[0] + 1;
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = r[0] + i;
            return all;
        }

        // 单值
        return new int[]{parseInt(field, lo, hi, name)};
    }

    private static int[] parseRange(String range, int lo, int hi, String name) {
        String[] sp = range.split("-", 2);
        if (sp.length != 2) {
            throw new IllegalArgumentException(name + " bad range: " + range);
        }
        int a = parseInt(sp[0], lo, hi, name);
        int b = parseInt(sp[1], lo, hi, name);
        if (a > b) {
            throw new IllegalArgumentException(
                    name + " range start > end: " + range);
        }
        return new int[]{a, b};
    }

    private static int parseInt(String s, int lo, int hi, String name) {
        int v;
        try {
            v = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " value not integer: " + s);
        }
        if (v < lo || v > hi) {
            throw new IllegalArgumentException(
                    name + " value " + v + " out of range [" + lo + "," + hi + "]");
        }
        return v;
    }

    private static int[] toSortedDedupedArray(List<Integer> values) {
        return values.stream().distinct().sorted().mapToInt(Integer::intValue).toArray();
    }

    /** 数组中是否含某值。线性扫描 —— 5 字段 cron 数组都很小(最多 60),不值得 BitSet。 */
    private static boolean hit(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) return true;
        }
        return false;
    }

    private CronExpression() {
        // utility class
    }
}
