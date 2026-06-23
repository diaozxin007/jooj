package com.xilidou.marvis.harness.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.harness.JacksonConfig;
import com.xilidou.marvis.harness.http.AnthropicClient;
import com.xilidou.marvis.harness.http.dto.ContentBlock;
import com.xilidou.marvis.harness.http.dto.CreateMessageRequest;
import com.xilidou.marvis.harness.http.dto.CreateMessageResponse;
import com.xilidou.marvis.harness.http.dto.MessageParam;
import com.xilidou.marvis.harness.http.dto.TextBlock;
import com.xilidou.marvis.harness.http.dto.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory Selection 子系统 —— LLM side-query 选 ≤ N 个相关 memory + 关键词回退。
 *
 * <p>对应 Python s09 的 {@code select_relevant_memories} + {@code load_memories}。
 *
 * <p>核心策略:
 * <ol>
 *   <li>从最近 user 消息收集对话文本(最多 3 条,2000 字符上限)</li>
 *   <li>列出所有 memory 文件的 catalog(name + description)</li>
 *   <li>调 LLM:"对照对话和 catalog,返回相关 memory 索引(JSON 数组)"</li>
 *   <li>失败回退:在最近 user 文本里抽 length>3 的关键词,跟 name+description
 *       做 case-insensitive substring 匹配</li>
 *   <li>限制 ≤ {@code maxItems}(默认 5)</li>
 * </ol>
 *
 * <p>为什么不用 embedding:教学版规模小(< 200 个 memory),线性扫够用;
 * LLM 选比 embedding 选**更准**——"用户偏好 tab"和"用户讨厌空格"
 * 在 embedding 空间不近,但 LLM 知道是同一回事。
 *
 * <p>调用时机(由 AgentLoop 安排):
 * <ul>
 *   <li>每个 user turn 开始时调一次 {@link #load} → 注入到当前 turn 的 user 消息前</li>
 *   <li>不每轮都调,以免烧 token + 破坏 prompt cache</li>
 * </ul>
 *
 * <p>失败优雅降级:
 * <ul>
 *   <li>无 memory 文件 → 返回空</li>
 *   <li>LLM 调用抛异常 → 关键词回退</li>
 *   <li>LLM 返回不含 JSON 数组 → 关键词回退</li>
 *   <li>JSON 解析失败 → 关键词回退</li>
 *   <li>关键词回退也找不到 → 返回空(不 crash)</li>
 * </ul>
 */
@Slf4j
public class MemorySelector {

    /** 摘要 LLM 调用 max_tokens(只让它返回短数组,200 token 足够)。*/
    private static final int SELECT_MAX_TOKENS = 200;

    /** 收集最近 user 文本的条数上限。*/
    private static final int RECENT_USER_LIMIT = 3;

    /** 收集最近 user 文本的总字符数上限。*/
    private static final int RECENT_TEXT_MAX_CHARS = 2000;

    /** JSON 数组提取 pattern:从 LLM 返回的文本里抠 [...] 一段。*/
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

    /** 关键词回退:只取 length > 3 的词(避开 the/and 这种噪音)。*/
    private static final int KEYWORD_MIN_LEN = 3;

    /**
     * Selector 的 system prompt(不污染主 agent 的 SYSTEM_PROMPT)。
     */
    private static final String SELECT_SYSTEM =
            "You are a memory selector. Output ONLY a JSON array of integers, no preamble.";

    private final MemoryStore store;
    private final AnthropicClient client;
    private final String model;
    private final int maxItems;
    private final ObjectMapper json;

    /**
     * @param store    存储层(读 catalog 用)
     * @param client   LLM 客户端(side-query 用),null = 直接走关键词回退
     * @param model    模型 ID(client 非 null 时必填)
     * @param maxItems 最多返回多少个 memory,默认 5
     */
    public MemorySelector(MemoryStore store, AnthropicClient client, String model, int maxItems) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (maxItems <= 0) throw new IllegalArgumentException("maxItems must be > 0");
        if (client != null && (model == null || model.isBlank())) {
            throw new IllegalArgumentException("model required when client provided");
        }
        this.store = store;
        this.client = client;
        this.model = model;
        this.maxItems = maxItems;
        this.json = JacksonConfig.newMapper();
    }

    /** 默认 maxItems=5 的简化构造。*/
    public MemorySelector(MemoryStore store, AnthropicClient client, String model) {
        this(store, client, model, 5);
    }

    // ─────────────────────────────────────────────────────────────
    //  对外 API
    // ─────────────────────────────────────────────────────────────

    /**
     * 选出与最近对话相关的 memory 文件名(≤ maxItems)。
     *
     * @param messages 对话历史(只读最近的 user 文本)
     * @return 选中的文件名(空列表 = 无相关或无 memory)
     */
    public List<String> select(List<MessageParam> messages) {
        List<MemoryFile> files = store.list();
        if (files.isEmpty()) return List.of();

        String recent = collectRecentUserText(messages);
        if (recent.isBlank()) return List.of();

        // Path 1: LLM side-query
        if (client != null) {
            try {
                List<String> selected = selectViaLlm(files, recent);
                if (selected != null) return selected;
            } catch (Exception e) {
                log.warn("[Memory] LLM selection failed, falling back to keywords: {}", e.toString());
            }
        }

        // Path 2: 关键词回退
        return selectViaKeywords(files, recent);
    }

    /**
     * 加载选中 memory 的全文,拼成 {@code <relevant_memories>...</relevant_memories>}
     * 字符串。AgentLoop 把这段字符串拼到当前 user turn 之前注入。
     *
     * @return 拼好的字符串(无相关 memory 时返回空字符串)
     */
    public String load(List<MessageParam> messages) {
        List<String> selected = select(messages);
        if (selected.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("<relevant_memories>\n");
        for (String filename : selected) {
            store.read(filename).ifPresent(mf -> {
                sb.append("\n<memory name=\"").append(mf.getName()).append("\">\n");
                sb.append(mf.getBody() == null ? "" : mf.getBody().strip()).append("\n");
                sb.append("</memory>\n");
            });
        }
        sb.append("</relevant_memories>");
        log.info("[Memory] loaded {} relevant memories", selected.size());
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    //  实现细节
    // ─────────────────────────────────────────────────────────────

    /**
     * 从 messages 末尾往前扫,收集最近 {@link #RECENT_USER_LIMIT} 条 user 文本,
     * 拼成一段不超过 {@link #RECENT_TEXT_MAX_CHARS} 字符的对话上下文。
     *
     * <p>跳过 tool_result(那是模型自己拿到的数据,不是用户表达的诉求)。
     */
    private String collectRecentUserText(List<MessageParam> messages) {
        if (messages == null || messages.isEmpty()) return "";

        List<String> recentTexts = new ArrayList<>();
        // 倒序遍历,只看 user role,跳过 tool_result
        for (int i = messages.size() - 1; i >= 0 && recentTexts.size() < RECENT_USER_LIMIT; i--) {
            MessageParam m = messages.get(i);
            if (!"user".equals(m.getRole())) continue;
            String text = extractUserText(m);
            if (!text.isBlank()) {
                recentTexts.add(text);
            }
        }
        // 倒回正序
        Collections.reverse(recentTexts);
        String joined = String.join(" ", recentTexts);
        if (joined.length() > RECENT_TEXT_MAX_CHARS) {
            joined = joined.substring(0, RECENT_TEXT_MAX_CHARS);
        }
        return joined;
    }

    /** 从 user 消息里抽文本(纯字符串 / TextBlock 列表)。tool_result 的 content 不算用户输入。*/
    private static String extractUserText(MessageParam m) {
        Object c = m.getContent();
        if (c instanceof String s) return s;
        if (c instanceof List<?> blocks) {
            // 全是 tool_result 的 user 消息跳过(空字符串)
            boolean allToolResults = !blocks.isEmpty()
                    && blocks.stream().allMatch(b -> b instanceof ToolResultBlock);
            if (allToolResults) return "";
            // 取所有 TextBlock 的 text 拼起来
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

    /**
     * 调 LLM,prompt 里给 catalog,要求返回 JSON 数组索引。
     * 解析失败 / 抛异常 → 返回 null,调用方走关键词回退。
     */
    private List<String> selectViaLlm(List<MemoryFile> files, String recent) {
        // 构 catalog: "0: name — description"
        StringBuilder catalog = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            MemoryFile f = files.get(i);
            catalog.append(i).append(": ").append(f.getName())
                    .append(" — ").append(f.getDescription() == null ? "" : f.getDescription())
                    .append('\n');
        }

        String prompt =
                "Given the recent conversation and the memory catalog below, " +
                        "select the indices of memories that are clearly relevant. " +
                        "Return ONLY a JSON array of integers, e.g. [0, 3]. " +
                        "If none are relevant, return [].\n\n" +
                        "Recent conversation:\n" + recent + "\n\n" +
                        "Memory catalog:\n" + catalog;

        CreateMessageRequest req = CreateMessageRequest.builder()
                .model(model)
                .maxTokens(SELECT_MAX_TOKENS)
                .system(SELECT_SYSTEM)
                .messages(List.of(MessageParam.user(prompt)))
                .build();

        CreateMessageResponse resp = client.createMessage(req);
        String text = resp.firstText();
        if (text == null || text.isBlank()) return null;

        // 抠 JSON 数组
        Matcher matcher = JSON_ARRAY.matcher(text);
        if (!matcher.find()) return null;

        try {
            int[] indices = json.readValue(matcher.group(), int[].class);
            List<String> out = new ArrayList<>();
            for (int idx : indices) {
                if (idx < 0 || idx >= files.size()) continue;
                String filename = files.get(idx).getFilename();
                if (filename != null && !out.contains(filename)) {
                    out.add(filename);
                    if (out.size() >= maxItems) break;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[Memory] LLM returned malformed JSON: {}", text);
            return null;
        }
    }

    /**
     * 关键词回退:从 recent 抽 length > {@link #KEYWORD_MIN_LEN} 的词,
     * 跟每条 memory 的 {@code name + description} 做 case-insensitive substring 匹配。
     *
     * <p>是 LLM side-query 失败时的兜底,精度比 LLM 差但永远不会失败。
     */
    private List<String> selectViaKeywords(List<MemoryFile> files, String recent) {
        // 抽关键词
        List<String> keywords = new ArrayList<>();
        for (String w : recent.split("\\s+")) {
            // 去标点
            String cleaned = w.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (cleaned.length() > KEYWORD_MIN_LEN) {
                keywords.add(cleaned);
            }
        }
        if (keywords.isEmpty()) return List.of();

        List<String> selected = new ArrayList<>();
        for (MemoryFile f : files) {
            if (f.getFilename() == null) continue;
            String nd = ((f.getName() == null ? "" : f.getName()) + " "
                    + (f.getDescription() == null ? "" : f.getDescription())).toLowerCase();
            for (String kw : keywords) {
                if (nd.contains(kw)) {
                    selected.add(f.getFilename());
                    break;
                }
            }
            if (selected.size() >= maxItems) break;
        }
        return selected;
    }
}
