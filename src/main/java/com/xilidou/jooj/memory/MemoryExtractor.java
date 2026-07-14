package com.xilidou.jooj.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.llm.LlmClient;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory Extraction 子系统 —— LLM 从对话中提取 fact 写入 Storage。
 *
 * <p>对应 Python s09 的 {@code extract_memories(messages)}。
 *
 * <p>触发时机:每个 turn 自然停顿点(stop_reason != "tool_use"),
 * 由 AgentLoop 调用一次。这样既能抓住对话关键信息,又不烧太多 token。
 *
 * <p>核心策略:
 * <ol>
 *   <li>取最近 N 条消息(默认 10),拼成对话文本</li>
 *   <li>列出已有 memory 的 catalog(name + description),让 LLM 知道哪些已有</li>
 *   <li>调 LLM:"提取新 fact,跳过已覆盖的,返回 JSON 数组"</li>
 *   <li>解析 JSON,逐条调 {@link MemoryStore#write} 落盘</li>
 * </ol>
 *
 * <p>失败处理 —— **没有回退路径**:
 * <ul>
 *   <li>LLM 调用失败 → log.warn + 这次跳过,等下次自然停顿点再试</li>
 *   <li>JSON 解析失败 → log.warn + 这次跳过</li>
 *   <li>单条 fact 缺字段 → 跳过该条,继续处理其他</li>
 *   <li>跟 Selector 不同:Selector "挑"可以回退关键词,Extractor "造"没法回退—
 *       规则代码写不出 description 概括句</li>
 * </ul>
 *
 * <p>跟 Selector 共享的设计:
 * <ul>
 *   <li>独立 SYSTEM prompt,不污染主 agent 上下文</li>
 *   <li>不传 tools(纯文本 IO)</li>
 *   <li>正则 + Jackson 双重防御 JSON 解析</li>
 * </ul>
 *
 * <p>不做的事(留给 Consolidator):
 * <ul>
 *   <li>不去重已有 memory(让 LLM 自己看 catalog 判断,模型不该重写已存在的)</li>
 *   <li>不删旧 memory(覆盖式语义:同 name 让 store.write 覆盖,不主动 delete)</li>
 *   <li>不合并相似 memory(那是 Consolidator 的活)</li>
 * </ul>
 */
@Slf4j
public class MemoryExtractor {

    /** Extractor LLM 调用 max_tokens(返回 JSON 数组,800 token 一般够 5-10 条 fact)。*/
    private static final int EXTRACT_MAX_TOKENS = 800;

    /** 收集对话的消息条数上限(只看最近 N 条,避免 prompt 自己爆)。*/
    private static final int RECENT_MESSAGE_LIMIT = 10;

    /** 拼接后的对话文本字符数上限。*/
    private static final int DIALOGUE_MAX_CHARS = 4000;

    /** JSON 数组提取 pattern:贪婪匹配,跨多行(可能含嵌套对象)。*/
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*\\]", Pattern.DOTALL);

    /** Extractor 的独立 SYSTEM prompt。*/
    private static final String EXTRACT_SYSTEM =
            "You are a memory extractor. Output ONLY a JSON array, no preamble.";

    private final MemoryStore store;
    private final LlmClient client;
    private final String model;
    private final ObjectMapper json;

    /**
     * @param store  存储层(写文件用)
     * @param client canonical vendor-neutral LLM 客户端(提取用),null = 禁用 Extractor
     * @param model  模型 ID(client 非 null 时必填)
     */
    public MemoryExtractor(MemoryStore store, LlmClient client, String model) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (client != null && (model == null || model.isBlank())) {
            throw new IllegalArgumentException("model required when client provided");
        }
        this.store = store;
        this.client = client;
        this.model = model;
        this.json = JsonMappers.newMapper();
    }

    /**
     * 从对话中提取并写入 fact。
     *
     * @param messages 对话历史(只读最近 {@link #RECENT_MESSAGE_LIMIT} 条)
     * @return 实际写入的 memory 数量(0 = 没新 fact 或调用失败)
     */
    public int extract(List<MessageParam> messages) {
        if (client == null) {
            return 0; // Extractor 禁用
        }
        if (messages == null || messages.isEmpty()) return 0;

        String dialogue = renderRecentDialogue(messages);
        if (dialogue.isBlank()) return 0;

        String existing = renderExistingCatalog();

        String prompt = buildExtractionPrompt(dialogue, existing);

        // 调 LLM(canonical vendor-neutral 路径)
        String text;
        try {
            LlmRequest req = LlmRequest.builderWithSystemText(EXTRACT_SYSTEM)
                    .model(model)
                    .maxTokens(EXTRACT_MAX_TOKENS)
                    .messages(List.of(LlmMessage.userText(prompt)))
                    .build();
            LlmResponse resp = client.createMessage(req);
            text = resp.firstText();
        } catch (Exception e) {
            log.warn("[Memory] extraction LLM call failed, skipping: {}", e.toString());
            return 0;
        }

        if (text == null || text.isBlank()) {
            return 0;
        }

        // 解析 JSON
        List<Map<String, Object>> items = parseExtractedItems(text);
        if (items == null || items.isEmpty()) return 0;

        // 逐条写入
        int written = 0;
        for (Map<String, Object> item : items) {
            try {
                MemoryFile mem = toMemoryFile(item);
                if (mem == null) continue;
                store.write(mem);
                written++;
            } catch (Exception e) {
                log.warn("[Memory] failed to write extracted item {}: {}", item, e.toString());
            }
        }
        if (written > 0) {
            log.info("[Memory] extracted {} new memories", written);
        }
        return written;
    }

    // ─────────────────────────────────────────────────────────────
    //  实现细节
    // ─────────────────────────────────────────────────────────────

    /**
     * 取最近 N 条消息,渲染成 {@code role: content} 文本。
     * 跳过 tool_use / tool_result(那是模型自己的工具调用,不是事实来源)。
     */
    String renderRecentDialogue(List<MessageParam> messages) {
        int from = Math.max(0, messages.size() - RECENT_MESSAGE_LIMIT);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < messages.size(); i++) {
            MessageParam m = messages.get(i);
            String text = extractTextContent(m);
            if (text.isBlank()) continue;
            sb.append(m.getRole()).append(": ").append(text).append('\n');
        }
        String out = sb.toString();
        if (out.length() > DIALOGUE_MAX_CHARS) {
            // 从尾部保留(最近的优先)
            out = out.substring(out.length() - DIALOGUE_MAX_CHARS);
        }
        return out.strip();
    }

    /**
     * 从消息里抽取文本部分。
     * String content → 直接返回
     * List<ContentBlock> → 拼所有 TextBlock 的 text;全是 tool_result 时返回空
     */
    private static String extractTextContent(MessageParam m) {
        Object c = m.getContent();
        if (c instanceof String s) return s;
        if (c instanceof List<?> blocks) {
            // 全 tool_result 跳过
            boolean allToolResults = !blocks.isEmpty()
                    && blocks.stream().allMatch(b -> b instanceof ToolResultBlock);
            if (allToolResults) return "";

            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof TextBlock tb) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(tb.getText() == null ? "" : tb.getText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** 列已有 memory 的 catalog,让 LLM 不重复写。空时返回 "(none)"。*/
    private String renderExistingCatalog() {
        List<MemoryFile> existing = store.list();
        if (existing.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (MemoryFile m : existing) {
            sb.append("- ").append(m.getName()).append(": ")
                    .append(m.getDescription() == null ? "" : m.getDescription())
                    .append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * 构造 Extractor 的 prompt。
     *
     * <p>关键设计:
     * <ul>
     *   <li>明确 type 4 选 1(防止 LLM 自创 type)</li>
     *   <li>name 用 kebab-case(LLM 习惯写 snake_case 或 camelCase,需要明确指定)</li>
     *   <li>"If nothing new ... return []"(允许 LLM 说"无新信息")</li>
     *   <li>列出已有 memory 让 LLM 自己判断重复</li>
     * </ul>
     */
    static String buildExtractionPrompt(String dialogue, String existing) {
        return "Extract user preferences, constraints, or project facts from this dialogue.\n" +
                "Return a JSON array. Each item: {name, type, description, body}.\n" +
                "- name: short kebab-case identifier (e.g. 'user-preference-tabs')\n" +
                "- type: one of 'user' (user preference), 'feedback' (guidance), " +
                "'project' (project fact), 'reference' (external pointer)\n" +
                "- description: one-line summary for index lookup\n" +
                "- body: full detail in markdown\n" +
                "If nothing new or already covered by existing memories, return [].\n\n" +
                "Existing memories:\n" + existing + "\n\n" +
                "Dialogue:\n" + dialogue;
    }

    /**
     * 从 LLM 响应里抠出 JSON 数组并解析为 List<Map>。
     * 解析失败返回 null(调用方静默跳过)。
     */
    List<Map<String, Object>> parseExtractedItems(String text) {
        Matcher matcher = JSON_ARRAY.matcher(text);
        if (!matcher.find()) {
            log.warn("[Memory] no JSON array found in LLM response");
            return null;
        }
        try {
            return json.readValue(matcher.group(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("[Memory] failed to parse extracted JSON: {}", e.toString());
            return null;
        }
    }

    /**
     * 把 LLM 返回的一条 dict 转成 MemoryFile。缺必要字段返回 null。
     *
     * <p>必要字段:name, description, body(三者缺一不可)。
     * type 缺失或非法 → 默认 USER(由 {@link MemoryFile.Type#parse} 兜底)。
     */
    static MemoryFile toMemoryFile(Map<String, Object> item) {
        if (item == null) return null;
        String name = strField(item, "name");
        String desc = strField(item, "description");
        String body = strField(item, "body");
        String type = strField(item, "type");

        if (name == null || name.isBlank()) return null;
        if (desc == null || desc.isBlank()) return null;
        if (body == null || body.isBlank()) return null;

        return MemoryFile.of(name, MemoryFile.Type.parse(type), desc, body);
    }

    private static String strField(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
