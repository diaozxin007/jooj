package com.xilidou.jooj.http;

import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.llm.LlmClient;
import com.xilidou.jooj.llm.adapter.AnthropicAdapter;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * {@link AnthropicClient} 的测试用 Mock 实现。
 *
 * <p>设计目标:
 * <ol>
 *   <li><b>按顺序返回预设响应</b>:模拟多轮对话场景</li>
 *   <li><b>记录所有 request</b>:让测试可以验证 messages 累积是否正确</li>
 *   <li><b>支持错误注入</b>:用 {@code throwingResponder} 模拟 4xx/5xx</li>
 *   <li><b>支持 reset</b>:Spring 容器单例 mock 跨测试复用,@BeforeEach 重置 fixture</li>
 * </ol>
 *
 * <p>用法 1(顺序响应,跨测试不复用):
 * <pre>
 *   MockAnthropicClient mock = MockAnthropicClient.ofResponses(
 *       fixtureToolUse("bash", "ls"),
 *       fixtureEndTurn("done")
 *   );
 *   harness.agentLoop(messages);
 *   assertEquals(2, mock.getCallCount());
 * </pre>
 *
 * <p>用法 2(Spring 容器单例,跨测试复用):
 * <pre>
 *   @SpringBootTest @Import(JoojTestConfig.class)
 *   class FooTest {
 *       @Autowired MockAnthropicClient mock;
 *       @BeforeEach void setup() {
 *           mock.reset(MockAnthropicClient.sequential(fixtureEndTurn("ok")));
 *       }
 *   }
 * </pre>
 */
public class MockAnthropicClient implements AnthropicClient, LlmClient {

    /**
     * P2: canonical ↔ wire 桥接。所有 canonical 请求都通过 adapter 翻译成 wire,
     * 走同一条 responder 路径,让 fixture 保持在 wire shape 定义,mock 行为一致。
     */
    private final AnthropicAdapter adapter = new AnthropicAdapter(JsonMappers.newMapper());

    /**
     * 所有收到过的 request(顺序保留,最旧 → 最新)。
     */
    @Getter
    private final List<CreateMessageRequest> requests = new ArrayList<>();

    /**
     * 响应器:根据当前 request 决定返回什么。允许 reset。
     */
    private Function<CreateMessageRequest, CreateMessageResponse> responder;

    /**
     * 通用构造器:接受一个动态响应函数。
     */
    public MockAnthropicClient(Function<CreateMessageRequest, CreateMessageResponse> responder) {
        this.responder = responder;
    }

    /**
     * 便利工厂:按顺序返回固定响应列表。
     */
    public static MockAnthropicClient ofResponses(CreateMessageResponse... responses) {
        return new MockAnthropicClient(sequential(responses));
    }

    /**
     * 便利工厂:每次调用都抛指定异常。
     */
    public static MockAnthropicClient throwing(RuntimeException error) {
        return new MockAnthropicClient(req -> { throw error; });
    }

    /**
     * 便利工厂:按顺序消费响应的 responder(给 reset 用)。
     */
    public static Function<CreateMessageRequest, CreateMessageResponse> sequential(
            CreateMessageResponse... responses) {
        return new SequentialResponder(List.of(responses));
    }

    /**
     * 重置 mock 状态:清空请求历史 + 替换 responder。
     *
     * <p>给 Spring 容器单例 mock 跨测试复用用 —— 在 @BeforeEach 里调用,
     * 保证每个测试拿到干净的 mock。
     */
    public void reset(Function<CreateMessageRequest, CreateMessageResponse> newResponder) {
        this.requests.clear();
        this.responder = newResponder;
    }

    /**
     * 重置为按顺序返回固定响应列表。
     */
    public void reset(CreateMessageResponse... responses) {
        reset(sequential(responses));
    }

    @Override
    public CreateMessageResponse createMessage(CreateMessageRequest req) {
        // 拍快照:复制 messages 列表,避免 AgentLoopHarness 后续修改 List 影响断言
        requests.add(snapshot(req));
        return responder.apply(req);
    }

    /**
     * P2 canonical entrypoint. Translates the incoming {@link LlmRequest} to wire shape,
     * delegates to the wire responder (records the wire request for existing assertions),
     * then translates the wire response back to canonical. Wire-side {@link AnthropicException}s
     * thrown by fixtures are classified into canonical {@link com.xilidou.jooj.llm.domain.LlmException}
     * so callers on the P2 path see the expected error type.
     */
    @Override
    public LlmResponse createMessage(LlmRequest req) {
        CreateMessageRequest wire = adapter.toWire(req);
        try {
            CreateMessageResponse resp = createMessage(wire);
            return adapter.toDomain(resp);
        } catch (AnthropicException e) {
            throw adapter.classify(e);
        }
    }

    /**
     * 浅拷贝 request:把 messages 列表复制一份。
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

    public int getCallCount() {
        return requests.size();
    }

    public CreateMessageRequest getLastRequest() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("No requests received yet");
        }
        return requests.get(requests.size() - 1);
    }

    // ── 内部:顺序响应器 ────────────────────────────────────────
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
