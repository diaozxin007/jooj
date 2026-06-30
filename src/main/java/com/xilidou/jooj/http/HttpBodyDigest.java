package com.xilidou.jooj.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * HTTP body 的可读 digest 工具(s21 Demo 25 副作用 v2)。
 *
 * <h3>问题</h3>
 *
 * <p>{@link AnthropicHttpClient} 把完整的 request / response body 当 log 输出之后,撞上几类
 * 结构性噪音 —— 字段对协议必要,但对人眼调试零价值,而且体积惊人:
 *
 * <ul>
 *   <li><b>{@code signature}</b>:thinking block 的加密签名。base64 编码,~3000+ 字符,
 *       完全不可读,但每次 thinking turn 都有,占 log 90%+ 体积</li>
 *   <li><b>{@code thinking}</b>:extended thinking 文本。短的几百字,长的几 KB,
 *       通常诊断只需要前几句确认 LLM 在想什么</li>
 *   <li><b>{@code content}(tool_result)</b>:工具回放给 LLM 的内容。整个文件 / 命令输出,
 *       动辄 KB 起步 —— 跟 Demo 24 L3 BudgetCompactor 落盘那条线对应,但 log 不需要全文</li>
 *   <li><b>{@code text}</b>:assistant TextBlock 文本。短的几十字,长的几 KB(模型一次性长输出)</li>
 * </ul>
 *
 * <h3>设计</h3>
 *
 * <p><b>不是脱敏(redaction),是 digest(摘要)</b>:目的是让 log 一眼看清结构和意图,
 * 不是隐藏 sensitive 信息(那是另一回事,跟 weixin 的 {@code Redaction} 工具区分)。
 *
 * <ul>
 *   <li><b>已知冗长字段截断</b>:超过阈值的 string 截到 head + 标注原长度,如:
 *       {@code "thinking":"The dialogue contains... <truncated 4198 chars>"}</li>
 *   <li><b>signature 直接夷平</b>:全是 base64 噪音,留个标记就行,
 *       如 {@code "signature":"<3120 chars>"}</li>
 *   <li><b>其它字段原样</b>:role / type / id / stop_reason / usage 这种结构性元数据保留</li>
 *   <li><b>整体兜底</b>:最终结果若仍超过 hard cap,从尾部截断标注 {@code ...&lt;truncated total N chars&gt;}</li>
 * </ul>
 *
 * <p>以纯函数 + 静态方法暴露,无外部依赖(传 {@link ObjectMapper} 进来,不持有状态)。
 *
 * <h3>不在范围内</h3>
 *
 * <ul>
 *   <li>不做 PII / token / api_key 脱敏 —— Anthropic body 本身不带 auth header
 *       (header 在 OkHttp 层另外 log,jooj 当前不 log header,所以这事不存在)</li>
 *   <li>不动 weixin 的 {@code Redaction} —— 那是 url query / token 脱敏,职责正交</li>
 *   <li>不格式化 / 美化 JSON —— log 一行,不换行,跟现有 SLF4J 模式一致</li>
 * </ul>
 */
public final class HttpBodyDigest {

    /** 单个 string 字段截断阈值(字符)。超过会被截断到这个长度并标注原长。 */
    public static final int FIELD_TRUNCATE_THRESHOLD = 200;

    /** 整个 digest 输出的 hard cap(字符)。超过尾部截断兜底。 */
    public static final int TOTAL_CAP = 4096;

    /** 已知"全噪音"字段:整段夷平为 {@code <N chars>} 不留 preview。 */
    private static final java.util.Set<String> NOISE_ONLY_FIELDS = java.util.Set.of(
            "signature"
    );

    /**
     * 已知"长但偶尔有用"字段:超过阈值时截断保留 head。
     * 不在这个集合里的 string 字段不动(role / type / id / model / stop_reason 等都很短)。
     */
    private static final java.util.Set<String> LONG_FIELDS = java.util.Set.of(
            "thinking",
            "text",
            "content",
            "input_schema"
    );

    private HttpBodyDigest() {
        // utility
    }

    /**
     * 把 raw JSON body 字符串转成可读的 digest。失败时(非 JSON / 空)原样返回 + 兜底截断。
     *
     * @param rawBody     原 body(可能是大 JSON / 错误响应 / 空)
     * @param json        Jackson mapper(线程安全,共用一个就行)
     * @return 截断 + 摘要后的 string,适合一行 log
     */
    public static String digest(String rawBody, ObjectMapper json) {
        if (rawBody == null || rawBody.isEmpty()) return "";
        if (json == null) {
            return capLength(rawBody);
        }
        // 不是 JSON(error 文本 / status 502 HTML 等)→ 直接整体截断
        String trimmed = rawBody.trim();
        if (trimmed.isEmpty() || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
            return capLength(rawBody);
        }
        try {
            JsonNode root = json.readTree(rawBody);
            JsonNode summarized = summarize(root, json);
            String out = json.writeValueAsString(summarized);
            return capLength(out);
        } catch (JsonProcessingException e) {
            // 解析失败 → 原样兜底
            return capLength(rawBody);
        }
    }

    /**
     * 递归压缩 JsonNode 里已知冗长字段。返回新节点(不 mutate 原 root)。
     */
    private static JsonNode summarize(JsonNode node, ObjectMapper json) {
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            ObjectNode out = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode v = e.getValue();
                out.set(key, summarizeField(key, v, json));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode out = json.createArrayNode();
            for (JsonNode child : arr) {
                out.add(summarize(child, json));
            }
            return out;
        }
        // value node:string/num/bool — 不动
        return node;
    }

    /** 处理 (key, value) 一对:string field 走截断规则,其他 value / nested 节点走递归。 */
    private static JsonNode summarizeField(String key, JsonNode value, ObjectMapper json) {
        if (value == null) return null;
        if (value.isTextual()) {
            String s = value.textValue();
            // 全噪音字段:夷平
            if (NOISE_ONLY_FIELDS.contains(key)) {
                return TextNode.valueOf("<" + s.length() + " chars>");
            }
            // 长字段:超阈值截断
            if (LONG_FIELDS.contains(key) && s.length() > FIELD_TRUNCATE_THRESHOLD) {
                String head = s.substring(0, FIELD_TRUNCATE_THRESHOLD);
                return TextNode.valueOf(head + "... <truncated " + s.length() + " chars>");
            }
            // 普通 string:不动
            return value;
        }
        // nested object/array 递归
        if (value.isObject() || value.isArray()) {
            return summarize(value, json);
        }
        return value;
    }

    /** 整体硬截断兜底:超过 TOTAL_CAP 切尾部加标记。 */
    private static String capLength(String s) {
        if (s == null) return "";
        if (s.length() <= TOTAL_CAP) return s;
        int kept = TOTAL_CAP;
        return s.substring(0, kept) + "... <truncated total " + s.length() + " chars>";
    }

    // ── 给非 ObjectMapper 持有方的便利重载(测试 / log 内联用) ───────

    /** 不依赖外部 ObjectMapper 的便利重载 —— 内部 new 一个,对单次调用足够。 */
    static String digest(String rawBody) {
        return digest(rawBody, new ObjectMapper());
    }

    // 暴露常量方便外部按需引用
    @SuppressWarnings("unused")
    public static List<String> longFieldsForTest() {
        return List.copyOf(LONG_FIELDS);
    }
}
