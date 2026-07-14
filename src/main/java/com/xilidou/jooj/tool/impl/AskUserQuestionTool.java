package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.AgentInterruptedException;
import com.xilidou.jooj.agent.SessionContext;
import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.AskTimeoutException;
import com.xilidou.jooj.agent.control.ChoiceAnswer;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * s22 AQ:{@code ask_user_question} tool —— 让 LLM 主动向用户提问,阻塞等答复。
 *
 * <p>对齐 Anthropic Claude Code SDK 的 AskUserQuestion 特性:
 * <ul>
 *   <li>1-4 个问题(每问 2-4 options)</li>
 *   <li>每 option 有 {@code label} + 可选 {@code description}</li>
 *   <li>可 {@code multiSelect}(用户勾多个)</li>
 *   <li>{@code header} 12 字符内短标签(chip/tag)</li>
 *   <li><b>UI 自动追加 "Other" 选项 + 文本框</b>—— LLM 不需要写,用户选中后
 *       会回传 {@code "Other: <用户文本>"}</li>
 * </ul>
 *
 * <h3>使用协议</h3>
 *
 * <p>LLM 调 {@code ask_user_question}:
 * <pre>
 *   {
 *     "questions": [
 *       {
 *         "question": "用哪个 UI 库?",
 *         "header": "UI lib",
 *         "options": [
 *           {"label": "React", "description": "生态最大"},
 *           {"label": "Vue",   "description": "上手最快"}
 *         ],
 *         "multiSelect": false
 *       }
 *     ]
 *   }
 * </pre>
 *
 * <p>tool 内部 {@code agentControl.ask(sid, ClarifyQuestion, 3min)} 挂起,
 * REST {@code GET /pending} 返 question,用户 {@code POST /answer} → tool 拿到
 * {@link ChoiceAnswer}, 序列化成 JSON 塞回 LLM(下一轮 LLM 看到用户的选择)。
 *
 * <h3>失败保护</h3>
 * <ul>
 *   <li>schema 验证失败(参数缺 / options < 2 / header 空)→ 明确错误消息</li>
 *   <li>SessionContext 无 sid → 返 Error(说明不该在 test 直接调)</li>
 *   <li>timeout / interrupt → tool_result 返"user didn't answer"或"interrupted"</li>
 * </ul>
 */
@Component
@Slf4j
public class AskUserQuestionTool implements Tool {

    /** 默认阻塞超时 —— 匹配 WebUserApprover 的 3min。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);

    private final AgentControl agentControl;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public AskUserQuestionTool(AgentControl agentControl) {
        this(agentControl, DEFAULT_TIMEOUT);
    }

    /** 测试用:可注入短 timeout。 */
    public AskUserQuestionTool(AgentControl agentControl, Duration timeout) {
        this.agentControl = agentControl;
        this.timeout = timeout;
    }

    @Override
    public String getName() {
        return "ask_user_question";
    }

    @Override
    public String getDescription() {
        return "Ask the human user 1-4 multiple-choice questions and get their answers. " +
                "Use this when you need clarification on decisions the user must make " +
                "(library choice, approach, feature scope, etc.). Blocks until user answers. " +
                "**Do not add an 'Other' option** — the UI automatically appends one with a " +
                "text box so the user can type a custom value. When the user picks it, you'll " +
                "receive the answer as 'Other: <user text>'.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        // JSON schema:{ questions: [{question, header, options: [{label, description?}], multiSelect?}] }
        Map<String, Object> optionSchema = new LinkedHashMap<>();
        optionSchema.put("type", "object");
        optionSchema.put("properties", Map.of(
                "label", Map.of("type", "string", "description",
                        "Short user-visible option text (≤5 chars ideally)."),
                "description", Map.of("type", "string", "description",
                        "Explanation of what this option means / its trade-offs.")
        ));
        optionSchema.put("required", List.of("label"));

        Map<String, Object> subQuestionSchema = new LinkedHashMap<>();
        subQuestionSchema.put("type", "object");
        subQuestionSchema.put("properties", Map.of(
                "question", Map.of("type", "string", "description",
                        "The complete question. Should end with a question mark."),
                "header", Map.of("type", "string", "description",
                        "Short label (≤12 chars) shown as a chip/tag."),
                "options", Map.of("type", "array",
                        "description", "2-4 options.",
                        "items", optionSchema,
                        "minItems", 2, "maxItems", 4),
                "multiSelect", Map.of("type", "boolean", "description",
                        "If true, user may pick multiple options. Default false.")
        ));
        subQuestionSchema.put("required", List.of("question", "header", "options"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("questions", Map.of(
                "type", "array",
                "description", "1-4 questions to ask the user.",
                "items", subQuestionSchema,
                "minItems", 1, "maxItems", 4));

        return List.of(new ToolDefinition(
                "ask_user_question",
                getDescription(),
                InputSchema.object(props, "questions")));
    }

    @Override
    public String summary(ToolCall call) {
        // 前端 loading 气泡看:"❓ 提问 (N)" 或第一个问题 header
        if (call == null || call.getArguments() == null) return "❓ ask user";
        try {
            Object rawQs = call.getArguments().get("questions");
            if (rawQs instanceof List<?> list && !list.isEmpty()) {
                if (list.size() == 1 && list.get(0) instanceof Map<?, ?> m) {
                    Object h = m.get("header");
                    if (h != null) return "❓ " + h;
                }
                return "❓ ask user (" + list.size() + ")";
            }
        } catch (Throwable ignore) {
        }
        return "❓ ask user";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!"ask_user_question".equals(call.getToolName())) {
            return new ToolResult(false, "Unknown tool: " + call.getToolName());
        }
        Map<String, Object> args = call.getArguments();
        if (args == null) return new ToolResult(false, "Error: 'questions' is required");

        // sid 从 ThreadLocal(D-10-C SessionContext),lead / subagent / teammate 都能拿到
        String sid = SessionContext.current();
        if (sid == null || sid.isBlank()) {
            log.warn("[AskUserQuestion] no sid in context — cannot ask user");
            return new ToolResult(false,
                    "Error: no session context, ask_user_question requires a live user session");
        }

        // 解析 questions
        Object rawQs = args.get("questions");
        if (!(rawQs instanceof List<?> rawList) || rawList.isEmpty()) {
            return new ToolResult(false, "Error: 'questions' must be a non-empty array");
        }
        List<ClarifyQuestion.SubQuestion> parsed = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> m)) {
                return new ToolResult(false, "Error: each question must be an object");
            }
            try {
                parsed.add(parseSubQuestion(m));
            } catch (IllegalArgumentException iae) {
                return new ToolResult(false, "Error: " + iae.getMessage());
            }
        }

        ClarifyQuestion question;
        try {
            // s22 D-12:从 SessionContext 拿 channel + peerId,让 PresenterRegistry 分派时
            // WeixinPresenter 能反查投递地址;web 场景两个都是 null(SSE 按 sid 路由不需要)
            String channel = SessionContext.currentChannel();
            String peerId = SessionContext.currentPeerId();
            question = ClarifyQuestion.of(parsed, channel, peerId);
        } catch (IllegalArgumentException iae) {
            return new ToolResult(false, "Error: " + iae.getMessage());
        }

        log.info("[AskUserQuestion] escalating sid={} askId={} numQuestions={}",
                sid, question.askId(), parsed.size());
        try {
            Answer answer = agentControl.ask(sid, question, timeout);
            return new ToolResult(true, formatAnswer(answer));
        } catch (AskTimeoutException ate) {
            log.warn("[AskUserQuestion] sid={} askId={} timed out", sid, ate.getAskId());
            return new ToolResult(false,
                    "User did not respond within " + timeout.toMinutes() + " minutes; "
                            + "consider making a reasonable default decision or asking a simpler question.");
        } catch (AgentInterruptedException aie) {
            log.info("[AskUserQuestion] sid={} interrupted during ask", sid);
            // interrupt 期间让 loop 检查点抛,tool_result 就返 interrupted 说明
            return new ToolResult(false, "[User interrupted during ask_user_question]");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, "Thread interrupted while waiting for user answer");
        }
    }

    private ClarifyQuestion.SubQuestion parseSubQuestion(Map<?, ?> m) {
        String question = str(m.get("question"));
        String header = str(m.get("header"));
        Object rawOpts = m.get("options");
        if (!(rawOpts instanceof List<?> rawOptList)) {
            throw new IllegalArgumentException("options must be an array");
        }
        List<ClarifyQuestion.Option> opts = new ArrayList<>();
        for (Object o : rawOptList) {
            if (!(o instanceof Map<?, ?> om)) {
                throw new IllegalArgumentException("each option must be an object");
            }
            String label = str(om.get("label"));
            String description = om.get("description") != null ? String.valueOf(om.get("description")) : null;
            opts.add(new ClarifyQuestion.Option(label, description));
        }
        boolean multi = false;
        Object rawMulti = m.get("multiSelect");
        if (rawMulti instanceof Boolean b) multi = b;
        return new ClarifyQuestion.SubQuestion(question, header, opts, multi);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 把 ChoiceAnswer 转成 LLM 能直接理解的 JSON 字符串,塞回 tool_result。
     *
     * <p>格式:{@code {"0": ["React"], "1": ["Yes", "Add tests"]}}
     *
     * <p>其他 Answer 类型(理论不会走到,ChoiceAnswer 是本 tool 唯一预期)保守返 toString。
     */
    private String formatAnswer(Answer answer) {
        if (answer instanceof ChoiceAnswer c) {
            // 手工组一个稳定的 JSON —— 不依赖 Jackson,避免 test 里注入 mapper
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            Map<String, List<String>> selections = c.selections();
            // 按 key 排序,输出稳定(测试友好)
            List<String> keys = new ArrayList<>(selections.keySet());
            keys.sort(String::compareTo);
            for (String k : keys) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(k)).append("\":[");
                List<String> vs = selections.get(k);
                for (int i = 0; i < vs.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escape(vs.get(i))).append("\"");
                }
                sb.append("]");
            }
            sb.append("}");
            return sb.toString();
        }
        return String.valueOf(answer);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
