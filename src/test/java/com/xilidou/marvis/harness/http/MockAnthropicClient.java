package com.xilidou.marvis.harness.http;

import com.xilidou.marvis.harness.http.dto.CreateMessageRequest;
import com.xilidou.marvis.harness.http.dto.CreateMessageResponse;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * {@link AnthropicClient} 的测试用 Mock 实现。
 *
 * <p>设计目标：
 * <ol>
 *   <li><b>按顺序返回预设响应</b>：模拟多轮对话场景</li>
 *   <li><b>记录所有 request</b>：让测试可以验证 messages 累积是否正确</li>
 *   <li><b>支持错误注入</b>：用 {@code throwingResponder} 模拟 4xx/5xx</li>
 * </ol>
 *
 * <p>用法 1（顺序响应）：
 * <pre>
 *   MockAnthropicClient mock = MockAnthropicClient.ofResponses(
 *       fixtureToolUse("bash", "ls"),
 *       fixtureEndTurn("done")
 *   );
 *   harness.agentLoop(messages);
 *   assertEquals(2, mock.getCallCount());
 * </pre>
 *
 * <p>用法 2（动态响应器）：
 * <pre>
 *   MockAnthropicClient mock = new MockAnthropicClient(req -> {
 *       if (req.getMessages().size() == 1) return fixtureToolUse(...);
 *       else return fixtureEndTurn(...);
 *   });
 * </pre>
 */
public class MockAnthropicClient implements AnthropicClient {

    /**
     * 所有收到过的 request（顺序保留，最旧 → 最新）。
     * 测试可以通过 {@code getRequests().get(N)} 验证第 N 轮请求体。
     */
    @Getter
    private final List<CreateMessageRequest> requests = new ArrayList<>();

    /**
     * 响应器：根据当前 request（和已积累的调用历史）决定返回什么。
     */
    private final Function<CreateMessageRequest, CreateMessageResponse> responder;

    /**
     * 通用构造器：接受一个动态响应函数。
     */
    public MockAnthropicClient(Function<CreateMessageRequest, CreateMessageResponse> responder) {
        this.responder = responder;
    }

    /**
     * 便利工厂：按顺序返回固定响应列表。
     *
     * <p>第 N 次调用返回 responses[N]。如果调用次数超过响应数，抛 {@link NoSuchElementException}
     * （测试预期之外的多余调用 = bug）。
     */
    public static MockAnthropicClient ofResponses(CreateMessageResponse... responses) {
        List<CreateMessageResponse> list = List.of(responses);
        return new MockAnthropicClient(new SequentialResponder(list));
    }

    /**
     * 便利工厂：每次调用都抛指定异常。
     */
    public static MockAnthropicClient throwing(RuntimeException error) {
        return new MockAnthropicClient(req -> { throw error; });
    }

    @Override
    public CreateMessageResponse createMessage(CreateMessageRequest req) {
        // 拍快照：复制 messages 列表，避免 AgentLoopHarness 后续修改 List 影响断言
        // 生产代码（AnthropicHttpClient）调用时 OkHttp 立即把 req 序列化为 JSON 字符串，
        // 之后 List 怎么改都不影响请求体。但 Mock 只持有 req 引用，需要手动拷贝。
        requests.add(snapshot(req));
        return responder.apply(req);
    }

    /**
     * 浅拷贝 request：把 messages 列表复制一份。
     * messages 内的 MessageParam 不变（loop 不会原地修改单条 message）。
     */
    private static CreateMessageRequest snapshot(CreateMessageRequest req) {
        return CreateMessageRequest.builder()
                .model(req.getModel())
                .maxTokens(req.getMaxTokens())
                .system(req.getSystem())
                .messages(req.getMessages() != null
                        ? new ArrayList<>(req.getMessages())
                        : null)
                .tools(req.getTools() != null
                        ? new ArrayList<>(req.getTools())
                        : null)
                .temperature(req.getTemperature())
                .stopSequences(req.getStopSequences() != null
                        ? new ArrayList<>(req.getStopSequences())
                        : null)
                .build();
    }

    /**
     * 已被调用的次数。
     */
    public int getCallCount() {
        return requests.size();
    }

    /**
     * 最后一次收到的 request（断言"第 N 轮请求长什么样"用）。
     */
    public CreateMessageRequest getLastRequest() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("No requests received yet");
        }
        return requests.get(requests.size() - 1);
    }

    // ── 内部：顺序响应器 ────────────────────────────────────────
    private static class SequentialResponder
            implements Function<CreateMessageRequest, CreateMessageResponse> {

        private final List<CreateMessageResponse> responses;
        private int index = 0;

        SequentialResponder(List<CreateMessageResponse> responses) {
            this.responses = responses;
        }

        @Override
        public CreateMessageResponse apply(CreateMessageRequest req) {
            if (index >= responses.size()) {
                throw new NoSuchElementException(
                        "MockAnthropicClient: too many calls. Expected " + responses.size()
                                + " but got call #" + (index + 1));
            }
            return responses.get(index++);
        }
    }
}
