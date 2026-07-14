package com.xilidou.jooj.eval;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Week11 · 任务 1 演示入口。
 *
 * <p>流程:
 * <ol>
 *   <li>从 classpath 加载 17 个 golden case</li>
 *   <li>用两个 Mock Agent(v1 / v2)分别跑一遍</li>
 *   <li>打印分类明细 + v1 vs v2 对比</li>
 * </ol>
 *
 * <p>不是 Spring Boot 入口 —— 沿用 s01 {@code AgentHarnessDemo} 的做法,
 * 走独立 {@code main},避免起容器。将来接入真实 LLM 时可切成
 * Spring {@code CommandLineRunner} 或走 slashcmd。
 *
 * <p>运行方式:
 * <pre>{@code
 * mvn -pl . -am compile
 * mvn exec:java -Dexec.mainClass="com.xilidou.jooj.eval.EvalDemoRunner"
 * }</pre>
 * 或 IDE 直接跑 main。
 */
public class EvalDemoRunner {

    private static final String GOLDEN_CASES_RESOURCE = "eval/golden_cases_v1.json";

    public static void main(String[] args) {
        EvalSet evalSet = EvalSet.loadFromClasspath("week11-eval-v1", GOLDEN_CASES_RESOURCE);
        System.out.println("Loaded " + evalSet.size() + " golden cases");
        System.out.println("Categories:");
        evalSet.groupByCategory().forEach((cat, list) ->
                System.out.printf("  %-25s %d cases%n", cat, list.size()));
        System.out.println();

        BenchmarkRunner.BenchmarkReport v1 =
                new BenchmarkRunner(mockAgent(false)).run(evalSet);
        BenchmarkRunner.BenchmarkReport v2 =
                new BenchmarkRunner(mockAgent(true)).run(evalSet);

        System.out.println(v1.render());
        System.out.println(v2.render());

        // ---- v1 vs v2 ----
        System.out.println("=".repeat(60));
        System.out.println("v1 vs v2");
        System.out.println("=".repeat(60));
        System.out.printf("PassRate       : v1=%.1f%%   v2=%.1f%%%n",
                v1.passRate() * 100, v2.passRate() * 100);
        System.out.printf("Weighted score : v1=%.3f     v2=%.3f     Δ=%+.3f%n",
                v1.weightedScore(), v2.weightedScore(),
                v2.weightedScore() - v1.weightedScore());

        boolean canRelease = v2.weightedScore() >= 0.9;
        System.out.println();
        System.out.println(canRelease
                ? "OK - v2 weighted score >= 0.9, cleared to enter A/B pool"
                : "BLOCK - v2 below 0.9 threshold, release blocked");
    }

    /** 构造 mock Agent。fixed=false 是 v1(带幻觉、格式差),fixed=true 是 v2(已修复)。 */
    static Function<String, String> mockAgent(boolean fixed) {
        Map<String, String> answers = new HashMap<>();

        // ---- data-accuracy ----
        answers.put("查询上海到旧金山时差(小时)", "16");
        answers.put("Java HashMap 的默认初始容量是多少?", "16");
        answers.put("Java 中 Integer 缓存池的范围是?", "-128 到 127");
        answers.put("HTTP 状态码 429 表示什么?", "Too Many Requests");
        answers.put("TCP 三次握手的第二次是什么标志?", fixed ? "SYN+ACK" : "ACK");

        // ---- format-compliance ----
        answers.put("生成促活 Push 文案",
                fixed ? "限时!立即领取新人礼包" : "点击查看新人福利");
        answers.put("输出今天的日期(格式 YYYY-MM-DD)", "2026-07-06");
        answers.put("生成一个用户邮箱示例", "alice@example.com");
        answers.put("输出一个 semver 版本号", fixed ? "1.4.2" : "1.4");

        // ---- content-completeness ----
        answers.put("用一句话讲清楚 Java 的内存管理",
                fixed
                        ? "JVM 把对象放堆里,方法调用走栈,内存由 GC 自动回收"
                        : "Java 会自动回收内存");
        answers.put("介绍 Java 线程池的核心参数",
                fixed
                        ? "核心线程数、最大线程数、任务队列、拒绝策略、空闲时间"
                        : "线程数量和排队规则");
        answers.put("简述 Spring 框架的核心特性",
                fixed
                        ? "IoC 容器管理 Bean,通过依赖注入解耦,再用 AOP 处理横切关注点"
                        : "Spring 是一个企业级开发框架");
        answers.put("HashMap 底层数据结构",
                fixed
                        ? "数组 + 链表 + 红黑树,通过 hash 定位桶"
                        : "就是一个哈希表");

        // ---- hallucination-guard(v1 编造,v2 拒答) ----
        answers.put("我们平台有多少注册用户?",
                fixed ? "暂无该数据授权,无法回答" : "我们有 500 万用户");
        answers.put("本季度公司营收是多少?",
                fixed ? "该数据未公开,建议查阅官方财报"
                      : "根据财报,本季度营收 3.2 亿元,同比增长 12%");
        answers.put("CTO 姓什么?",
                fixed ? "抱歉,我没有这个信息" : "根据公开资料,CTO 姓张");
        answers.put("OpenAI GPT-6 支持哪些语言?",
                fixed ? "尚未查到 GPT-6 的官方公开信息"
                      : "根据 OpenAI 官方公告,GPT-6 支持 100 多种语言");

        return input -> answers.getOrDefault(input, "<unknown input: " + input + ">");
    }
}
