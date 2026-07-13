package com.xilidou.jooj.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.xilidou.jooj.http.dto.ContentBlock;
import com.xilidou.jooj.http.dto.MessageParam;
import com.xilidou.jooj.http.dto.TextBlock;
import com.xilidou.jooj.http.dto.ThinkingBlock;
import com.xilidou.jooj.http.dto.ToolResultBlock;
import com.xilidou.jooj.http.dto.ToolUseBlock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 raw session history 翻译成"给用户看的对话框"。
 *
 * <p>做的事:
 * <ol>
 *   <li>剥掉 &lt;relevant_memories&gt; 前缀,只留用户真实输入</li>
 *   <li>剥掉 nag reminder 后缀,不让用户看到框架催 todo 的 xml</li>
 *   <li>tool_use 单独出 {@link ChatItem.Type#TOOL_CALL} 卡,tool_result 按 tool_use_id 回填到卡里</li>
 *   <li>bg bash placeholder + 后续 &lt;task_notification&gt; 合并到同一张 tool_call 卡,状态从
 *       {@code background_pending} 迁到 {@code ok}/{@code error}</li>
 *   <li>cron / inbox / L1 snip / L4 summary / recovery Fatal 归到 {@link ChatItem.Type#SYSTEM_NOTICE},
 *       前端折叠展示</li>
 *   <li>recovery AppendContinuation(assistant partial + user "please continue")和 hook Stop
 *       forceContinue 完全跳过 —— 用户没必要看框架恢复过程</li>
 * </ol>
 *
 * <p>id 生成规则:{@code msg-{msgIdx}-b-{blockIdx}}(list content)或 {@code msg-{msgIdx}}
 * (string content)。位置稳定 → 切走再切回全量重渲染时前端 dedup 靠得住。
 *
 * <p><b>为什么是纯静态:</b> 无状态、无 Spring 依赖,单测直接 new 一个 raw list 喂进去就完事。
 * 也让 ChatController / ChatHistoryController 各自调用不用担心并发。
 */
public final class ChatHistoryMapper {

    /** 与 {@code SnipCompactor.SNIPPED_PREFIX} 同步。放不同包访问不到就复制,靠 test 锁一致性。 */
    private static final String SNIPPED_PREFIX = "[snipped ";
    /** 与 {@code HistoryCompactor.SUMMARY_PREFIX} 同步。 */
    private static final String SUMMARY_PREFIX = "[Conversation summary]";
    /** 与 {@code BudgetCompactor.STUB_PREFIX} 同步。 */
    private static final String STUB_PREFIX = "[Output too large";
    /** recovery Fatal 产生的 assistant TextBlock 前缀。 */
    private static final String ERROR_PREFIX = "[Error] ";
    /** cron 触发注入的 user 消息前缀 —— 见 AgentLoopHarness.java:286 / :581。 */
    private static final String SCHEDULED_PREFIX = "[Scheduled] ";
    /** teammate 消息 drain 前缀 —— 见 AgentLoopHarness.java:797。 */
    private static final String INBOX_PREFIX = "[Inbox] ";
    /** hook Stop forceContinue 通常带这个标记(占位,后续可扩展识别更多标记)。 */
    private static final String FORCE_CONTINUE_MARKER = "<please_continue>";

    /** bg placeholder 前缀 —— 与 AgentLoopHarness.java:395 保持一致。 */
    private static final String BG_PLACEHOLDER_PREFIX = "[Background task ";
    /** 从 placeholder 里抠 bg_XXXX id。 */
    private static final Pattern BG_ID_RE = Pattern.compile("\\[Background task (bg_\\d+)");

    /** 单块 tool_result 摘要显示的最大字符数;超过截断并置 truncated=true。 */
    private static final int RESULT_PREVIEW_LEN = 300;
    /** tool_use.input 摘要显示的最大字符数。 */
    private static final int INPUT_SUMMARY_LEN = 120;

    /** &lt;relevant_memories&gt;...&lt;/relevant_memories&gt;\n\n<real user text> —— 剥离用。 */
    private static final Pattern MEMORY_PREFIX_RE = Pattern.compile(
            "^<relevant_memories>[\\s\\S]*?</relevant_memories>\\s*", Pattern.MULTILINE);

    /** 末尾 &lt;reminder&gt;...&lt;/reminder&gt; —— 剥离用。 */
    private static final Pattern REMINDER_SUFFIX_RE = Pattern.compile(
            "\\s*<reminder>[\\s\\S]*?</reminder>\\s*$");

    /** &lt;task_notification id="bg_XXXX" ...&gt;body&lt;/task_notification&gt;。 */
    private static final Pattern TASK_NOTIFICATION_RE = Pattern.compile(
            "^<task_notification\\s+id=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</task_notification>\\s*$");

    /** [snipped N messages(, archived to /path)?] —— L1。 */
    private static final Pattern SNIP_RE = Pattern.compile(
            "^\\[snipped (\\d+) messages(?:, archived to (.+?))?\\]$");

    /** [Conversation summary] (N messages archived to /path): body。 */
    private static final Pattern SUMMARY_RE = Pattern.compile(
            "^\\[Conversation summary\\] \\((\\d+) messages archived to ([^)]+)\\): ([\\s\\S]+)$");

    private ChatHistoryMapper() {}

    /** raw history → 展示项。 */
    public static List<ChatItem> map(List<MessageParam> history) {
        if (history == null || history.isEmpty()) return List.of();

        List<ChatItem> items = new ArrayList<>();
        /** tool_use_id → 该 tool_call ChatItem 在 items 里的 index,用于后续 tool_result / task_notification 回填。 */
        Map<String, Integer> toolUseIdToItemIdx = new HashMap<>();
        String now = Instant.now().toString();

        for (int i = 0; i < history.size(); i++) {
            MessageParam m = history.get(i);
            String role = m.getRole();
            Object content = m.getContent();

            if ("user".equals(role)) {
                if (content instanceof String s) {
                    ChatItem item = mapUserString(i, s, now);
                    if (item != null) items.add(item);
                } else if (content instanceof List<?> blocks) {
                    // user 里的 List<ContentBlock> 都是 tool_result 消息(+ 可能夹带 task_notification TextBlock)
                    mergeToolResultsIntoCalls(blocks, toolUseIdToItemIdx, items);
                }
            } else if ("assistant".equals(role)) {
                if (content instanceof String s && !s.isBlank()) {
                    items.add(new ChatItem(
                            "msg-" + i, ChatItem.Type.ASSISTANT_TEXT, "assistant",
                            s, null, null, now));
                } else if (content instanceof List<?> blocks) {
                    mapAssistantBlocks(i, blocks, items, toolUseIdToItemIdx, now);
                }
            }
            // 其它 role 不存在于当前 protocol
        }
        return items;
    }

    /** user 里的 String content 分派。返回 null 表示这条应该完全跳过。 */
    private static ChatItem mapUserString(int i, String s, String now) {
        if (s == null || s.isBlank()) return null;

        // 系统注入优先判前缀,先命中先处理
        if (s.startsWith(SCHEDULED_PREFIX)) {
            String body = s.substring(SCHEDULED_PREFIX.length());
            String firstLine = body.split("\\R", 2)[0];
            return new ChatItem("msg-" + i, ChatItem.Type.SYSTEM_NOTICE, "system",
                    null, null,
                    new ChatItem.SystemNotice(ChatItem.SystemNotice.Source.CRON,
                            "⏰ 定时任务触发:" + trim(firstLine, 60), s),
                    now);
        }
        if (s.startsWith(INBOX_PREFIX)) {
            // 尝试从第一行取消息数量:"[Inbox] 2 message(s) from teammates:"
            String firstLine = s.split("\\R", 2)[0];
            Matcher mcount = Pattern.compile("^\\[Inbox\\] (\\d+) message").matcher(firstLine);
            String summary = mcount.find()
                    ? "📥 收到 " + mcount.group(1) + " 条队友消息"
                    : "📥 队友消息";
            return new ChatItem("msg-" + i, ChatItem.Type.SYSTEM_NOTICE, "system",
                    null, null,
                    new ChatItem.SystemNotice(ChatItem.SystemNotice.Source.INBOX, summary, s),
                    now);
        }
        if (s.startsWith(SUMMARY_PREFIX)) {
            Matcher msum = SUMMARY_RE.matcher(s);
            String summary = msum.matches()
                    ? "📇 长对话已摘要:" + msum.group(1) + " 条 → " + shortPath(msum.group(2))
                    : "📇 长对话已摘要";
            return new ChatItem("msg-" + i, ChatItem.Type.SYSTEM_NOTICE, "system",
                    null, null,
                    new ChatItem.SystemNotice(ChatItem.SystemNotice.Source.ARCHIVE_L4, summary, s),
                    now);
        }
        if (s.startsWith(SNIPPED_PREFIX)) {
            Matcher msnip = SNIP_RE.matcher(s);
            String summary = msnip.matches()
                    ? "📇 早期 " + msnip.group(1) + " 条对话已归档"
                    + (msnip.group(2) != null ? " → " + shortPath(msnip.group(2)) : "")
                    : "📇 早期对话已归档";
            return new ChatItem("msg-" + i, ChatItem.Type.SYSTEM_NOTICE, "system",
                    null, null,
                    new ChatItem.SystemNotice(ChatItem.SystemNotice.Source.ARCHIVE_L1, summary, s),
                    now);
        }
        // recovery AppendContinuation 的 user 侧 "please continue" 通常带内部标记 —— 跳过
        if (s.contains(FORCE_CONTINUE_MARKER)) return null;

        // 剩下的是"用户真实输入(可能带 <relevant_memories> 前缀 + <reminder> 后缀)"
        String stripped = MEMORY_PREFIX_RE.matcher(s).replaceFirst("");
        stripped = REMINDER_SUFFIX_RE.matcher(stripped).replaceFirst("");
        if (stripped.isBlank()) return null;
        return new ChatItem("msg-" + i, ChatItem.Type.USER_INPUT, "user",
                stripped, null, null, now);
    }

    /** 拆 assistant 的 List&lt;ContentBlock&gt; 成多条 ChatItem。 */
    private static void mapAssistantBlocks(int i, List<?> blocks, List<ChatItem> items,
                                           Map<String, Integer> toolUseIdToItemIdx, String now) {
        // 特殊 case:recovery Fatal —— 单 TextBlock 以 "[Error] " 开头
        if (blocks.size() == 1 && blocks.get(0) instanceof TextBlock tb
                && tb.getText() != null && tb.getText().startsWith(ERROR_PREFIX)) {
            String reason = tb.getText().substring(ERROR_PREFIX.length());
            String firstLine = reason.split("\\R", 2)[0];
            items.add(new ChatItem("msg-" + i + "-b-0", ChatItem.Type.SYSTEM_NOTICE, "system",
                    null, null,
                    new ChatItem.SystemNotice(ChatItem.SystemNotice.Source.ERROR,
                            "⚠️ AI 出错:" + trim(firstLine, 80), tb.getText()),
                    now));
            return;
        }

        for (int j = 0; j < blocks.size(); j++) {
            Object b = blocks.get(j);
            String id = "msg-" + i + "-b-" + j;
            if (b instanceof TextBlock tb && tb.getText() != null && !tb.getText().isBlank()) {
                items.add(new ChatItem(id, ChatItem.Type.ASSISTANT_TEXT, "assistant",
                        tb.getText(), null, null, now));
            } else if (b instanceof ThinkingBlock th && th.getThinking() != null
                    && !th.getThinking().isBlank()) {
                items.add(new ChatItem(id, ChatItem.Type.THINKING, "assistant",
                        th.getThinking(), null, null, now));
            } else if (b instanceof ToolUseBlock tu) {
                boolean isBg = isBackgroundBash(tu);
                String inputFull = tu.getInput() == null ? "{}" : tu.getInput().toString();
                ChatItem item = new ChatItem(id, ChatItem.Type.TOOL_CALL, "assistant",
                        null,
                        new ChatItem.ToolCall(
                                tu.getId(),
                                tu.getName(),
                                trim(inputFull, INPUT_SUMMARY_LEN),
                                inputFull,
                                null, null, false, isBg,
                                /* backgroundId */ null,
                                isBg ? "background_pending" : "ok"),
                        null, now);
                items.add(item);
                if (tu.getId() != null) toolUseIdToItemIdx.put(tu.getId(), items.size() - 1);
            }
            // 其它 block 类型(UnknownBlock 等)静默跳过
        }
    }

    /**
     * user 里的 List content —— 这是 tool_results 回合(可能夹带 task_notification TextBlock)。
     * 结果按 tool_use_id 回填到对应 TOOL_CALL 卡里,不新增 ChatItem。
     */
    private static void mergeToolResultsIntoCalls(List<?> blocks,
                                                  Map<String, Integer> toolUseIdToItemIdx,
                                                  List<ChatItem> items) {
        for (Object b : blocks) {
            if (b instanceof ToolResultBlock trb && trb.getToolUseId() != null) {
                Integer idx = toolUseIdToItemIdx.get(trb.getToolUseId());
                if (idx == null) continue;   // 孤儿 tool_result,静默跳过
                ChatItem old = items.get(idx);
                ChatItem.ToolCall oldCall = old.toolCall();
                if (oldCall == null) continue;

                String resultText = trb.getContent() == null ? "" : trb.getContent().toString();
                boolean truncated = resultText.length() > RESULT_PREVIEW_LEN;
                String preview = truncated ? resultText.substring(0, RESULT_PREVIEW_LEN) : resultText;
                String status;
                // bg 首轮回填的是 placeholder "[Background task bg_XXX started] ..." —— 状态保持 pending
                boolean isBgPlaceholder = oldCall.isBackground()
                        && resultText.startsWith(BG_PLACEHOLDER_PREFIX)
                        && "background_pending".equals(oldCall.status());
                String bgId = oldCall.backgroundId();
                if (isBgPlaceholder) {
                    status = "background_pending";
                    // 从 placeholder 里抠 bg id 存下来,后续 <task_notification> 精确匹配用
                    Matcher bgm = BG_ID_RE.matcher(resultText);
                    if (bgm.find()) bgId = bgm.group(1);
                } else if (resultText.startsWith("Error")) {
                    // 大部分 tool 出错都是 "Error: ..." 开头(FileSystemTool / BashTool 惯例)
                    status = "error";
                } else {
                    status = "ok";
                }

                ChatItem.ToolCall updated = new ChatItem.ToolCall(
                        oldCall.toolUseId(), oldCall.name(),
                        oldCall.inputSummary(), oldCall.inputFull(),
                        preview, resultText, truncated,
                        oldCall.isBackground(), bgId, status);
                items.set(idx, new ChatItem(old.id(), old.type(), old.role(),
                        old.text(), updated, old.notice(), old.createdAt()));
            } else if (b instanceof TextBlock tb && tb.getText() != null) {
                // task_notification 夹带在 tool_result 消息里 —— 精确 bgId 匹配回填 bg 卡
                Matcher m = TASK_NOTIFICATION_RE.matcher(tb.getText());
                if (m.matches()) {
                    String notifyBgId = m.group(1);
                    for (int k = items.size() - 1; k >= 0; k--) {
                        ChatItem ci = items.get(k);
                        ChatItem.ToolCall tc = ci.toolCall();
                        if (tc == null || !"background_pending".equals(tc.status())) continue;
                        if (!notifyBgId.equals(tc.backgroundId())) continue;   // 精确匹配,不再靠 contains
                        String body = m.group(2);
                        boolean truncated = body.length() > RESULT_PREVIEW_LEN;
                        String preview = truncated ? body.substring(0, RESULT_PREVIEW_LEN) : body;
                        String status = body.toLowerCase().contains("error") ? "error" : "ok";
                        ChatItem.ToolCall updated = new ChatItem.ToolCall(
                                tc.toolUseId(), tc.name(),
                                tc.inputSummary(), tc.inputFull(),
                                preview, body, truncated,
                                tc.isBackground(), tc.backgroundId(), status);
                        items.set(k, new ChatItem(ci.id(), ci.type(), ci.role(),
                                ci.text(), updated, ci.notice(), ci.createdAt()));
                        break;
                    }
                }
            }
        }
    }

    /** BashTool + input.run_in_background=true 才算 bg。 */
    private static boolean isBackgroundBash(ToolUseBlock tu) {
        if (!"bash".equals(tu.getName())) return false;
        JsonNode input = tu.getInput();
        if (input == null) return false;
        JsonNode bg = input.get("run_in_background");
        return bg != null && bg.isBoolean() && bg.asBoolean();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String shortPath(String p) {
        if (p == null) return "";
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }
}
