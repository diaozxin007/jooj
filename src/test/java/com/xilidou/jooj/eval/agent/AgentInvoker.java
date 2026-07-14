package com.xilidou.jooj.eval.agent;

/**
 * Agent 调用抽象层。
 *
 * <p>为什么单独抽出这层,不让 {@link com.xilidou.jooj.eval.BenchmarkRunner}
 * 直接依赖 {@link com.xilidou.jooj.http.AnthropicClient}:
 * <ul>
 *   <li><b>可替换性</b> —— Mock / 真 LLM / 未来带 tool loop 的
 *       {@code AgentLoopHarness} 都能实现这个接口,不用碰 Runner</li>
 *   <li><b>测试友好</b> —— {@code BenchmarkRunner} 的老测试用
 *       {@code Function<String, AgentInvocation>} 就够,不用起 Spring 容器</li>
 *   <li><b>责任隔离</b> —— 抓 token / 抓延时 / 兜异常是 Invoker 的活;
 *       Runner 只管调 Scorer 打分 + 聚合报告</li>
 * </ul>
 *
 * <p>契约:实现类**必须**捕获所有异常并转成 {@link AgentInvocation#errorReason}
 * 返回,而不能向上抛。这样 Runner 才能保持"某个 case 失败不拖垮整批评测"的行为。
 */
@FunctionalInterface
public interface AgentInvoker {

    /**
     * 用给定的用户输入调用一次 Agent。
     *
     * @param userInput golden case 的 input 字段(原样传入,不做二次包装)
     * @return 完整的调用结果,永不为 null,失败也是走 errorReason 字段
     */
    AgentInvocation invoke(String userInput);
}
