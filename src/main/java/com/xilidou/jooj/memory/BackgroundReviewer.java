package com.xilidou.jooj.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JacksonConfig;
import com.xilidou.jooj.http.AnthropicClient;
import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BackgroundReviewer —— Hermes Tier 3 P3.1 self-improvement review(s21 Demo 26)。
 *
 * <h3>跟 {@link MemoryExtractor} 的边界</h3>
 *
 * <p>两者**视角不同,产出归同一个 store**:
 *
 * <table>
 *   <caption>Extractor vs Reviewer</caption>
 *   <tr><th></th><th>Extractor (s09)</th><th>Reviewer (Demo 26)</th></tr>
 *   <tr><td>看什么</td><td>本轮对话有什么 *fact*</td><td>本轮跟历史比有什么 *重复纠正* / *持久工作流教训*</td></tr>
 *   <tr><td>典型产出</td><td>"用户偏好 tabs over spaces"</td><td>"用户两次纠正我用 ripgrep 不要用 grep"</td></tr>
 *   <tr><td>触发时机</td><td>同步 turn-end(阻塞下一轮 LLM)</td><td>异步 turn-end(unblock 后台跑)</td></tr>
 *   <tr><td>LLM prompt</td><td>抽事实</td><td>找模式 / 工作流教训</td></tr>
 *   <tr><td>type 主要落点</td><td>USER / PROJECT / REFERENCE</td><td>FEEDBACK(教训类)</td></tr>
 * </table>
 *
 * <h3>Hermes 的"重复纠正 + 持久工作流教训"判定</h3>
 *
 * <p>对应 Hermes 文档:
 * <blockquote>"consent-aware learning loop: repeated corrections and durable workflow lessons
 * become compact memory entries or procedural skills"</blockquote>
 *
 * <p>具体在 prompt 里强调 4 类信号(由 LLM 判断):
 * <ol>
 *   <li><b>重复纠正</b>:用户在不同 turn 反复纠正同一个方法 / 选择 / 风格</li>
 *   <li><b>persistent workflow lesson</b>:发现非平凡的工作流(先 oauth-refresh 再调 API)</li>
 *   <li><b>错误绕过</b>:撞墙后找到通路(npm install 用 --force)</li>
 *   <li><b>显式偏好声明</b>:"以后不要用 X" / "以后都用 Y"</li>
 * </ol>
 *
 * <p>**不重复 Extractor 已经处理的 fact** —— prompt 里明确说"已被 Extractor 抓到的事实跳过,
 * 只关注模式 / 教训"。Existing memory catalog 也注入,LLM 自己判断不重复。
 *
 * <h3>异步执行 + prefix cache 友好</h3>
 *
 * <p>由 {@link MemoryService#onTurnEnd} 提交到 {@code joojBgExecutor} 异步跑 ——
 * turn 完成后立即 unblock 让用户看到回复,review 在后台想几秒。Anthropic 5-min ephemeral
 * cache 让 review 的 input 拿到 prefix 命中(主 turn 刚跑完,cache 还热),边际成本极低。
 *
 * <p>**不重抛任何异常** —— review 失败只 warn,不挡 onTurnEnd 的 extract + consolidate
 * 主路径,更不挡用户下一轮输入。
 */
@Slf4j
public class BackgroundReviewer {

    /** Reviewer LLM 调用 max_tokens(返回提案 JSON 数组,500 token 一般够 1-3 条 lesson)。 */
    private static final int REVIEW_MAX_TOKENS = 500;

    /** 收集对话的消息条数上限(略大于 Extractor 的 10 条,因为要看"重复纠正"需要更长上下文)。 */
    private static final int RECENT_MESSAGE_LIMIT = 20;

    /** 拼接后的对话文本字符数上限。 */
    private static final int DIALOGUE_MAX_CHARS = 6000;

    /** JSON 数组提取 pattern。 */
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*\\]", Pattern.DOTALL);

    /** Reviewer 的独立 SYSTEM prompt。 */
    private static final String REVIEW_SYSTEM =
            "You are a self-improvement reviewer. Your job is to spot RECURRING corrections, " +
            "WORKFLOW lessons, or error workarounds that should become persistent memory. " +
            "Output ONLY a JSON array, no preamble. Skip raw facts that an extractor would catch — " +
            "you focus on PATTERNS that span multiple turns.";

    private final MemoryStore store;
    private final AnthropicClient client;
    private final String model;
    private final ObjectMapper json;

    /**
     * s21 Demo 27 / Hermes Tier 3 P3.2:可选 staged-write 通道。
     * null = 直接 store.write(老 Demo 26 行为);非 null + writeApproval=true = 走 staged。
     */
    private final PendingMemoryStore pendingStore;

    /** s21 Demo 27:true → 提案进 pending pool 等用户 approve;false → 直接生效。 */
    private final boolean writeApproval;

    /** 老 3 参 ctor —— 不接 staged,直接生效(Demo 26 等价行为)。 */
    public BackgroundReviewer(MemoryStore store, AnthropicClient client, String model) {
        this(store, client, model, null, false);
    }

    /**
     * 5 参 ctor —— Demo 27 起生产装配走这条。
     *
     * @param pendingStore   非 null 时启用 staged 路径(配合 writeApproval=true)
     * @param writeApproval  true → 提案进 pending pool;false → 直接 store.write
     */
    public BackgroundReviewer(MemoryStore store, AnthropicClient client, String model,
                              PendingMemoryStore pendingStore,
                              boolean writeApproval) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (client != null && (model == null || model.isBlank())) {
            throw new IllegalArgumentException("model required when client provided");
        }
        this.store = store;
        this.client = client;
        this.model = model;
        this.json = JacksonConfig.newMapper();
        this.pendingStore = pendingStore;
        this.writeApproval = writeApproval;
    }

    /**
     * 同步入口 —— 由 {@link MemoryService} 在 BgExecutor 里异步调。
     *
     * @param messages 完整对话历史(read-only)
     * @return 实际写入的 lesson 数量(0 = 没新模式 / 调用失败 / Reviewer 禁用)
     */
    public int review(List<MessageParam> messages) {
        if (client == null) return 0; // Reviewer 禁用
        if (messages == null || messages.size() < 4) {
            // 太短的对话谈不上模式,跳过避免无意义的 LLM 调用
            return 0;
        }

        String dialogue = renderRecentDialogue(messages);
        if (dialogue.isBlank()) return 0;

        String existing = renderExistingCatalog();
        String prompt = buildReviewPrompt(dialogue, existing);

        String text;
        try {
            CreateMessageRequest req = CreateMessageRequest.builder()
                    .model(model)
                    .maxTokens(REVIEW_MAX_TOKENS)
                    .system(REVIEW_SYSTEM)
                    .messages(List.of(MessageParam.user(prompt)))
                    .build();
            CreateMessageResponse resp = client.createMessage(req);
            text = resp.firstText();
        } catch (Exception e) {
            log.warn("[Memory:Review] LLM call failed, skipping: {}", e.toString());
            return 0;
        }

        if (text == null || text.isBlank()) return 0;

        List<Map<String, Object>> items = parseItems(text);
        if (items == null || items.isEmpty()) return 0;

        int written = 0;
        for (Map<String, Object> item : items) {
            try {
                MemoryFile mem = toMemoryFile(item);
                if (mem == null) continue;
                // s21 Demo 27 / P3.2:writeApproval=true + pendingStore 非 null →
                // 走 staged 路径,等用户 /memory approve。否则直接 store.write(Demo 26 行为)
                if (writeApproval && pendingStore != null) {
                    pendingStore.propose(mem, "reviewer");
                } else {
                    store.write(mem);
                }
                written++;
            } catch (Exception e) {
                log.warn("[Memory:Review] failed to write proposal {}: {}", item, e.toString());
            }
        }
        if (written > 0) {
            log.info("[Memory:Review] background review {} {} new lessons",
                    writeApproval && pendingStore != null ? "proposed (staged)" : "wrote",
                    written);
        }
        return written;
    }

    // ─────────────────────────────────────────────────────────────
    //  实现细节
    // ─────────────────────────────────────────────────────────────

    /** 取最近 N 条消息的文本部分。跟 MemoryExtractor 同款,但窗口大一倍。 */
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
            out = out.substring(out.length() - DIALOGUE_MAX_CHARS);
        }
        return out.strip();
    }

    private static String extractTextContent(MessageParam m) {
        Object c = m.getContent();
        if (c instanceof String s) return s;
        if (c instanceof List<?> blocks) {
            // 全 tool_result 的 message 跳过(它们是工具输出,不是模式来源 ——
            // 模式藏在用户的 text 跟 assistant 的 tool_use 选择里)
            boolean allToolResults = !blocks.isEmpty()
                    && blocks.stream().allMatch(b -> b instanceof ToolResultBlock);
            if (allToolResults) return "";

            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof TextBlock tb && tb.getText() != null) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(tb.getText());
                } else if (b instanceof ToolUseBlock tu) {
                    // tool_use name 保留 —— Reviewer 关心"用 grep 还是 ripgrep"这类工作流模式
                    if (sb.length() > 0) sb.append(' ');
                    sb.append("[used tool: ").append(tu.getName()).append("]");
                }
                // ToolResultBlock 在混合 message 里也跳过(过长 + 不是模式来源)
            }
            return sb.toString();
        }
        return "";
    }

    /** 列已有 memory 让 LLM 不重复提案。 */
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
     * 构造 Reviewer prompt。
     *
     * <p>关键差异(vs Extractor):
     * <ul>
     *   <li>"PATTERN" 而不是 "fact"</li>
     *   <li>显式说"如果只是单次事实,不要提案,留给 Extractor"</li>
     *   <li>type 强 default 到 'feedback'(教训类),其他 type 仍接受</li>
     *   <li>4 类信号清单(重复纠正 / 工作流 / 错误绕过 / 显式偏好)</li>
     * </ul>
     */
    static String buildReviewPrompt(String dialogue, String existing) {
        return "Review this dialogue for PATTERNS that should become persistent memory:\n" +
                "1. Repeated corrections — user corrected the same approach more than once\n" +
                "2. Workflow lessons — non-trivial multi-step recipes worth remembering\n" +
                "3. Error workarounds — solutions to obstacles that took effort\n" +
                "4. Explicit preferences — \"don't use X\" / \"always use Y\" statements\n\n" +
                "DO NOT propose:\n" +
                "- Single-shot facts (an extractor handles those separately)\n" +
                "- Anything already covered by existing memories below\n" +
                "- Speculation — only propose what the dialogue actually shows\n\n" +
                "Return JSON array. Each item: {name, type, description, body}.\n" +
                "- name: short kebab-case identifier (e.g. 'feedback-prefer-ripgrep')\n" +
                "- type: usually 'feedback' (workflow lesson). Use 'user' for personal " +
                "preference, 'project' for project-specific, 'reference' for pointer.\n" +
                "- description: one-line summary suitable for index lookup\n" +
                "- body: markdown explaining the pattern + when to apply\n\n" +
                "If no genuine pattern is observed, return [].\n\n" +
                "Existing memories:\n" + existing + "\n\n" +
                "Dialogue:\n" + dialogue;
    }

    /** 跟 Extractor 同款 JSON 抠出。 */
    List<Map<String, Object>> parseItems(String text) {
        Matcher matcher = JSON_ARRAY.matcher(text);
        if (!matcher.find()) {
            log.warn("[Memory:Review] no JSON array found in LLM response");
            return null;
        }
        try {
            return json.readValue(matcher.group(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("[Memory:Review] failed to parse JSON: {}", e.toString());
            return null;
        }
    }

    /** 跟 Extractor 同款 toMemoryFile 转换 —— type 缺失时默认 FEEDBACK 而不是 USER。 */
    static MemoryFile toMemoryFile(Map<String, Object> item) {
        if (item == null) return null;
        String name = strField(item, "name");
        String desc = strField(item, "description");
        String body = strField(item, "body");
        String type = strField(item, "type");

        if (name == null || name.isBlank()) return null;
        if (desc == null || desc.isBlank()) return null;
        if (body == null || body.isBlank()) return null;

        // type 缺失时 default=FEEDBACK(因为 Reviewer 主要产出"教训"而不是事实);
        // MemoryFile.Type.parse(null) 会走它自己的 default(USER),这里显式覆盖
        MemoryFile.Type resolvedType = (type == null || type.isBlank())
                ? MemoryFile.Type.FEEDBACK
                : MemoryFile.Type.parse(type);

        return MemoryFile.of(name, resolvedType, desc, body);
    }

    private static String strField(Map<String, Object> item, String key) {
        Object v = item.get(key);
        return v == null ? null : v.toString();
    }
}
