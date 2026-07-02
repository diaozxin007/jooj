package com.xilidou.jooj.http;

import com.xilidou.jooj.config.JacksonConfig;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.ToolDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * E2E 测试:真实调用 DeepSeek Anthropic 兼容端点。
 *
 * <p><b>需要环境变量 {@code DEEPSEEK_API_KEY} 设置为有效 key。</b>
 * CI 中不设置此环境变量则自动跳过。
 *
 * <p>验证:
 * <ul>
 *   <li>端到端文本对话</li>
 *   <li>端到端 Tool Calling(function calling)</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@DisplayName("DeepSeek E2E (requires DEEPSEEK_API_KEY)")
class DeepSeekE2ETest {

    private AnthropicHttpClient client;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        ObjectMapper json = JacksonConfig.newMapper();

        client = new AnthropicHttpClient(
                AnthropicHttpClient.defaultOkHttpClient(),
                json,
                "https://api.deepseek.com/anthropic",
                new ApiKeyAuth(apiKey),
                "deepseek",
                List.of("deepseek-")
        );
    }

    @Test
    @DisplayName("文本对话 — deepseek-chat 返回有效文本")
    void textConversation() {
        var req = CreateMessageRequest.builder()
                .model("deepseek-chat")
                .maxTokens(256)
                .messages(List.of(
                        MessageParam.user("What is 2 + 3? Answer with just the number.")
                ))
                .build();

        CreateMessageResponse resp = client.createMessage(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getId()).isNotBlank();
        assertThat(resp.getRole()).isEqualTo("assistant");
        assertThat(resp.getContent()).isNotEmpty();
        assertThat(resp.firstText()).contains("5");
        assertThat(resp.getStopReason()).isEqualTo("end_turn");
        assertThat(resp.getUsage()).isNotNull();
        assertThat(resp.getUsage().getInputTokens()).isGreaterThan(0);
        assertThat(resp.getUsage().getOutputTokens()).isGreaterThan(0);

        System.out.printf("[DeepSeek E2E] text: tokens in=%d out=%d, response='%s'%n",
                resp.getUsage().getInputTokens(),
                resp.getUsage().getOutputTokens(),
                resp.firstText().substring(0, Math.min(80, resp.firstText().length())));
    }

    @Test
    @DisplayName("Tool Calling — deepseek-chat 正确返回 tool_use")
    void toolCalling() {
        var weatherTool = new ToolDef(
                "get_weather",
                "Get current weather for a city",
                InputSchema.object(
                        Map.of("city", Map.of("type", "string", "description", "City name")),
                        "city"
                )
        );

        var req = CreateMessageRequest.builder()
                .model("deepseek-chat")
                .maxTokens(512)
                .tools(List.of(weatherTool))
                .messages(List.of(
                        MessageParam.user("What's the weather in Tokyo?")
                ))
                .build();

        CreateMessageResponse resp = client.createMessage(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getStopReason()).isEqualTo("tool_use");
        assertThat(resp.needsToolExecution()).isTrue();
        assertThat(resp.toolUses()).isNotEmpty();

        var toolUse = resp.toolUses().get(0);
        assertThat(toolUse.getName()).isEqualTo("get_weather");
        assertThat(toolUse.getId()).isNotBlank();
        assertThat(toolUse.getInput()).isNotNull();
        assertThat(toolUse.getInput().has("city")).isTrue();

        System.out.printf("[DeepSeek E2E] tool_use: name=%s, input=%s%n",
                toolUse.getName(), toolUse.getInput());
    }
}
