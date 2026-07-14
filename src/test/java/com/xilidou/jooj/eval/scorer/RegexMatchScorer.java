package com.xilidou.jooj.eval.scorer;

import com.xilidou.jooj.eval.GoldenCase;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则匹配:{@code actual} 必须整体匹配 {@code expected}(正则表达式)。
 *
 * <p>典型使用场景:格式合规。
 * <ul>
 *   <li>日期: {@code ^\d{4}-\d{2}-\d{2}$}</li>
 *   <li>Push 含 CTA: {@code .*(立即领取|马上参加).*}</li>
 *   <li>JSON 起手: {@code ^\{.*\}$}</li>
 * </ul>
 *
 * <p>Pattern 编译较贵,进程内做缓存 —— 50 个 case 反复跑评测时能明显省时。
 * 用 {@link ConcurrentHashMap} 是因为将来 BenchmarkRunner 若并发跑用例也不会挂。
 */
public class RegexMatchScorer implements Scorer {

    private static final ConcurrentHashMap<String, Pattern> CACHE = new ConcurrentHashMap<>();

    @Override
    public ScoreResult score(GoldenCase tc, String actual) {
        if (actual == null) return ScoreResult.fail("actual output is null");
        String pattern = tc.expected();
        if (pattern == null || pattern.isBlank()) {
            return ScoreResult.fail("expected regex is blank");
        }
        try {
            Pattern p = CACHE.computeIfAbsent(pattern, Pattern::compile);
            if (p.matcher(actual).matches()) {
                return ScoreResult.pass("regex matched: " + pattern);
            }
            return ScoreResult.fail("regex not matched: " + pattern);
        } catch (PatternSyntaxException e) {
            return ScoreResult.fail("bad regex in expected: " + e.getMessage());
        }
    }
}
