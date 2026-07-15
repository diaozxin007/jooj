package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmStopReason;
import com.xilidou.jooj.llm.domain.LlmText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * E2E 测试:真实调用 OpenAI Chat Completions 端点(P2 Step H)。
 *
 * <p><b>需要环境变量 {@code OPENAI_API_KEY} 设置为有效 key。</b>
 * CI 中不设置此环境变量则自动跳过。
 *
 * <p>验证:
 * <ul>
 *   <li>端到端文本对话(gpt-4o-mini)</li>
 *   <li>canonical LlmRequest / LlmResponse round-trip 通过 OpenAiChatAdapter</li>
 * </ul>
 *
 * <p>不覆盖:
 * <ul>
 *   <li>o1/o3 reasoning 模型(需要更高层账户,default-model 空跳过)</li>
 *   <li>Tool calling 双向 —— OpenAiChatAdapterTest 已覆盖 wire round-trip;
 *       real API 只跑 text-only 保证 endpoint 通路 OK</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("OpenAI E2E (requires OPENAI_API_KEY)")
class OpenAiE2ETest {

    private OpenAiHttpClient client;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        ObjectMapper json = JsonMappers.newMapper();

        client = new OpenAiHttpClient(
                OpenAiHttpClient.defaultOkHttpClient(),
                json,
                "https://api.openai.com",
                new BearerTokenAuth(apiKey)
        );
    }

    @Test
    @DisplayName("gpt-4o-mini 端到端文本对话")
    void end_to_end_text_conversation() {
        LlmRequest req = LlmRequest.builder()
                .model("gpt-4o-mini")
                .maxTokens(64)
                .system(List.of(new LlmText("You are a concise assistant. Reply in <= 8 words.")))
                .messages(List.of(LlmMessage.userText("Say 'hello from OpenAI' verbatim.")))
                .build();

        LlmResponse res = client.createMessage(req);

        assertThat(res.getStopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(res.firstText()).isNotBlank();
        assertThat(res.getModel()).contains("gpt-4o-mini");
        assertThat(res.getUsage()).isNotNull();
        assertThat(res.getUsage().getInputTokens()).isGreaterThan(0);
        assertThat(res.getUsage().getOutputTokens()).isGreaterThan(0);
    }

    @Test
    @DisplayName("provider name + prefixes 用于 ModelRouter 路由")
    void provider_identity() {
        assertThat(client.name()).isEqualTo("openai");
        assertThat(client.modelPrefixes()).contains("gpt-", "o1-", "o3-");
    }
}
