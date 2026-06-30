package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link HttpBodyDigest} 的核心行为(s21 Demo 25 副作用 v2):
 *
 * <ul>
 *   <li>signature 字段(全噪音)夷平成 {@code <N chars>}</li>
 *   <li>thinking / text / content 超阈值截断 + 标注原长</li>
 *   <li>普通短字段不动(role / type / id / model / stop_reason)</li>
 *   <li>非 JSON 输入兜底原样返回 + 整体截断</li>
 *   <li>嵌套 array / object 正确递归</li>
 *   <li>整体超 hard cap 末尾兜底截断</li>
 * </ul>
 */
class HttpBodyDigestTest {

    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper();
    }

    @Test
    @DisplayName("空 / null 输入安全")
    void empty_input_safe() {
        assertEquals("", HttpBodyDigest.digest(null, json));
        assertEquals("", HttpBodyDigest.digest("", json));
    }

    @Test
    @DisplayName("非 JSON 文本(error HTML / 502)整体截断兜底")
    void non_json_returns_capped_raw() {
        String html = "<html><body>502 Bad Gateway</body></html>";
        assertEquals(html, HttpBodyDigest.digest(html, json));
    }

    @Test
    @DisplayName("普通 JSON 短字段不动")
    void short_fields_untouched() {
        String body = "{\"model\":\"claude-sonnet-4-6\",\"role\":\"assistant\",\"id\":\"abc123\"}";
        String out = HttpBodyDigest.digest(body, json);
        // 用 readTree 互相比较语义而不是字符串(字段顺序可能不一致)
        assertNotNull(out);
        assertTrue(out.contains("claude-sonnet-4-6"));
        assertTrue(out.contains("\"role\":\"assistant\""));
        assertTrue(out.contains("\"id\":\"abc123\""));
    }

    @Test
    @DisplayName("signature 字段不论长短都夷平成 <N chars>")
    void signature_field_flattened() {
        String body = "{\"signature\":\"AAAAAAAA\",\"text\":\"hi\"}";
        String out = HttpBodyDigest.digest(body, json);
        assertTrue(out.contains("\"signature\":\"<8 chars>\""),
                "signature 应被夷平成 <8 chars>,实际:" + out);
        // text 短不动
        assertTrue(out.contains("\"text\":\"hi\""));
    }

    @Test
    @DisplayName("thinking 超阈值截断 + 标注原长")
    void thinking_truncated_with_length_marker() {
        String longThinking = "x".repeat(500);
        String body = "{\"thinking\":\"" + longThinking + "\",\"role\":\"assistant\"}";
        String out = HttpBodyDigest.digest(body, json);
        assertTrue(out.contains("<truncated 500 chars>"),
                "应标注原长 500,实际:" + out);
        // 应保留前 200 字符 head
        assertTrue(out.contains("x".repeat(200)));
        // role 不变
        assertTrue(out.contains("\"role\":\"assistant\""));
    }

    @Test
    @DisplayName("text 短(<=200)不动")
    void short_text_untouched() {
        String body = "{\"text\":\"hello world\",\"type\":\"text\"}";
        String out = HttpBodyDigest.digest(body, json);
        assertTrue(out.contains("\"text\":\"hello world\""),
                "短 text 应原样保留,实际:" + out);
    }

    @Test
    @DisplayName("嵌套 array(content blocks)递归处理")
    void nested_array_recurses() {
        String big = "y".repeat(400);
        String body = "{\"content\":[" +
                "{\"type\":\"thinking\",\"thinking\":\"" + big + "\",\"signature\":\"sigsigsig\"}," +
                "{\"type\":\"text\",\"text\":\"short answer\"}" +
                "]}";
        String out = HttpBodyDigest.digest(body, json);
        // thinking 被截
        assertTrue(out.contains("<truncated 400 chars>"));
        // signature 夷平
        assertTrue(out.contains("\"signature\":\"<9 chars>\""));
        // 短 text 保留
        assertTrue(out.contains("short answer"));
        // type 字段保留
        assertTrue(out.contains("\"type\":\"thinking\""));
        assertTrue(out.contains("\"type\":\"text\""));
    }

    @Test
    @DisplayName("tool_result 长 content 截断")
    void tool_result_long_content_truncated() {
        String big = "z".repeat(1000);
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"content\":\""
                + big + "\",\"tool_use_id\":\"toolu_xxx\"}]}]}";
        String out = HttpBodyDigest.digest(body, json);
        assertTrue(out.contains("<truncated 1000 chars>"));
        // tool_use_id 保留(短 string,不在 LONG_FIELDS)
        assertTrue(out.contains("\"tool_use_id\":\"toolu_xxx\""));
    }

    @Test
    @DisplayName("整体 digest 超过 TOTAL_CAP 兜底截断")
    void total_cap_enforced() {
        // 构造一个 message 列表,即使每条都被字段截断,数量太多还是会超 cap
        StringBuilder sb = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"user\",\"content\":\"msg ").append(i)
                    .append(" with some padding to fill space ").append("p".repeat(100))
                    .append("\"}");
        }
        sb.append("]}");
        String out = HttpBodyDigest.digest(sb.toString(), json);
        assertTrue(out.length() <= HttpBodyDigest.TOTAL_CAP + 100,
                "应被 hard cap 截到 ≤ TOTAL_CAP+marker,实际长度:" + out.length());
        if (out.length() > HttpBodyDigest.TOTAL_CAP) {
            assertTrue(out.endsWith("chars>"),
                    "尾部应有 truncated total marker,实际尾部:"
                            + out.substring(Math.max(0, out.length() - 60)));
        }
    }

    @Test
    @DisplayName("Demo 25 实战 case:Anthropic 响应 thinking + signature + text 一锅端")
    void demo25_real_anthropic_response() {
        String thinking = "The user asked about Beijing weather. ".repeat(50);  // ~1900 char
        String signature = "Es".repeat(1500);  // ~3000 char
        String body = "{\"model\":\"claude-sonnet-4-6\",\"id\":\"resp_001\"," +
                "\"content\":[" +
                "{\"type\":\"thinking\",\"thinking\":\"" + thinking + "\",\"signature\":\"" + signature + "\"}," +
                "{\"type\":\"text\",\"text\":\"It's sunny.\"}" +
                "]," +
                "\"stop_reason\":\"end_turn\"," +
                "\"usage\":{\"input_tokens\":2559,\"output_tokens\":468}}";

        String out = HttpBodyDigest.digest(body, json);
        // 关键诊断字段保留
        assertTrue(out.contains("\"model\":\"claude-sonnet-4-6\""));
        assertTrue(out.contains("\"id\":\"resp_001\""));
        assertTrue(out.contains("\"stop_reason\":\"end_turn\""));
        assertTrue(out.contains("\"input_tokens\":2559"));
        // 噪音字段被处理
        assertTrue(out.contains("\"signature\":\"<3000 chars>\""));
        assertTrue(out.contains("<truncated 1900 chars>"));
        // 短 text 保留
        assertTrue(out.contains("It's sunny."));

        // body 原长 ~5000+ chars,digest 后应该 < 1500
        int origLen = body.length();
        int digestLen = out.length();
        assertTrue(digestLen < origLen / 3,
                "digest 应该比原文短至少 3x。原:" + origLen + ", digest:" + digestLen);
    }
}
