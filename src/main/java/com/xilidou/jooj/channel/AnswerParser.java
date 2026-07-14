package com.xilidou.jooj.channel;

import com.xilidou.jooj.agent.control.ChoiceAnswer;
import com.xilidou.jooj.agent.control.ClarifyQuestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * s22 D-12:把用户在 IM channel (微信 / discord / ...) 里回复的**纯文本**
 * 解析成 {@link ChoiceAnswer}。web 端有结构化 UI 不需要,只 IM 用。
 *
 * <h3>识别的格式</h3>
 *
 * <p>假设有 2 个 sub-question,options 都是 4 个 {A,B,C,D}:
 * <ul>
 *   <li><b>单题单选</b>(只 1 个 sub-question):
 *     <ul>
 *       <li>{@code "A"} → {@code {"0":["A"]}}</li>
 *       <li>{@code "a"} → {@code {"0":["A"]}} (大小写不敏感)</li>
 *       <li>{@code "1A"} → 同上,允许显式带序号</li>
 *     </ul></li>
 *   <li><b>多题单选</b>:
 *     <ul>
 *       <li>{@code "1A 2B"} 或 {@code "1A,2B"} 或 {@code "1A2B"} → {@code {"0":["A"],"1":["B"]}}</li>
 *     </ul></li>
 *   <li><b>多题多选</b>(某题 multiSelect):
 *     <ul>
 *       <li>{@code "1A 2AB"} → 第二题选 A+B → {@code {"0":["A"],"1":["A","B"]}}</li>
 *       <li>{@code "1A 2A,B"} 或 {@code "1A 2A+B"} 都识别</li>
 *     </ul></li>
 *   <li><b>其它(自定义)</b> —— 前端 UI 里 "Other" 是最后一个选项,IM 里也一样。
 *     用户可以:
 *     <ul>
 *       <li>{@code "1E: Ruby on Rails"} — 显式选 E(自定义位)并给内容</li>
 *       <li>{@code "1 其它: gRPC"} 或 {@code "1其它 gRPC"} — 中文形式</li>
 *       <li>{@code "其它 xxx"}(单题场景) → {@code {"0":["Other: xxx"]}}</li>
 *     </ul></li>
 * </ul>
 *
 * <p><b>Other position</b>:约定 "Other" 是**每 sub-question options 之后的下一个字母**
 * ——如果 options 有 4 个 (A/B/C/D),Other = E;3 个 → D;2 个 → C。
 * 前端 UI 里 "Other" 也是渲染在最后位。IM 呈现文本时会列出 A-E,并注明 E 是"其它"。
 *
 * <p><b>大小写</b>:字母字段大小写不敏感,自定义文本原样保留。
 *
 * <p>不能识别的输入 → {@link Optional#empty()},让 caller 走"当作普通聊天消息"降级路径。
 */
public class AnswerParser {

    private AnswerParser() {}

    // 匹配 "1A" / "1 A" / "1: A" / "1. A" / "1E: xxx" / "1E xxx"(index + 字母 + 可选自由文本)
    // group(1)=index(可选,1-based),group(2)=字母段,group(3)=自定义文本(可选)
    private static final Pattern PER_QUESTION = Pattern.compile(
            "(?:^|\\s|[,;])" +
            "(?:([0-9]+)[.:、]?\\s*)?" +
            "([a-zA-Z]+)" +
            "(?:\\s*[:：]?\\s*(.+?))?" +
            "(?=\\s+[0-9]+[.:、]?[a-zA-Z]|,\\s*[0-9]|$)");

    // 单独识别 "其它 xxx" / "其他 xxx" / "Other xxx"(单题场景)
    private static final Pattern OTHER_PREFIX = Pattern.compile(
            "^\\s*(?:其[它他]|other)\\s*[:：]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 尝试从纯文本 parse 出 answer。
     *
     * @param text     用户回复
     * @param question 待答的 ClarifyQuestion(需要 sub-questions + options 结构做校验)
     * @return 成功 → ChoiceAnswer;失败 → empty(caller 应走"当聊天消息"降级)
     */
    public static Optional<ChoiceAnswer> tryParse(String text, ClarifyQuestion question) {
        if (text == null || question == null) return Optional.empty();
        String input = text.trim();
        if (input.isEmpty()) return Optional.empty();

        int nQuestions = question.questions().size();

        // 场景 A:只有 1 题 —— 允许极简输入,也支持 Other
        if (nQuestions == 1) {
            var sub = question.questions().get(0);
            // 试 "Other xxx" 形式
            Matcher om = OTHER_PREFIX.matcher(input);
            if (om.matches()) {
                return Optional.of(new ChoiceAnswer(Map.of("0",
                        List.of("Other: " + om.group(1).trim()))));
            }
            // 试单字母 "A" / "a"
            if (input.length() <= 3 && input.matches("(?i)[a-z]{1,3}")) {
                List<String> labels = resolveLetters(input, sub);
                if (labels != null && !labels.isEmpty()) {
                    if (!sub.multiSelect() && labels.size() > 1) return Optional.empty();
                    return Optional.of(new ChoiceAnswer(Map.of("0", labels)));
                }
            }
            // 落到通用多题 parser(允许 "1A" 显式序号)
        }

        // 通用:尝试用 "1A 2B" 格式 parse
        return tryParseIndexed(input, question);
    }

    /** 处理 "1A 2AB" / "1: A, 2: B" 等带索引格式。 */
    private static Optional<ChoiceAnswer> tryParseIndexed(String input, ClarifyQuestion question) {
        // 简化:tokenize by 空白 / 逗号 / 分号,每 token 独立解析
        String[] tokens = input.replace('，', ',').split("[\\s,;]+");
        Map<String, List<String>> selections = new LinkedHashMap<>();

        // token 格式:"1A" / "1a" / "1E:xxx" / "1: A" / "A"(隐式 idx)
        // group1 = 显式 idx(可选);group2 = 字母;group3 = 冒号后的自定义(可选)
        Pattern p = Pattern.compile(
                "^([0-9]+)?[.:、]?\\s*([a-zA-Z]+)(?:\\s*[:：]\\s*(.*))?$",
                Pattern.CASE_INSENSITIVE);

        int implicitIdx = 0;
        for (int t = 0; t < tokens.length; t++) {
            String tok = tokens[t];
            if (tok.isEmpty()) continue;

            Matcher m = p.matcher(tok);
            if (!m.matches()) return Optional.empty();

            String idxStr = m.group(1);
            String letters = m.group(2);
            String freeText = m.group(3);

            int qIdx = idxStr != null ? Integer.parseInt(idxStr) - 1 : implicitIdx++;
            if (qIdx < 0 || qIdx >= question.questions().size()) return Optional.empty();

            var sub = question.questions().get(qIdx);
            List<String> labels = resolveLetters(letters, sub);
            if (labels == null || labels.isEmpty()) return Optional.empty();

            // freeText 支持"跨 token": 如果这个 token 里没 freeText 但字母含 Other,
            // 尝试把**后续 token** 拼进来作为 freeText,直到遇到下一个"NxN 格式"
            if (freeText == null && labels.contains("Other")) {
                StringBuilder fb = new StringBuilder();
                while (t + 1 < tokens.length && !p.matcher(tokens[t + 1]).matches()) {
                    if (fb.length() > 0) fb.append(" ");
                    fb.append(tokens[++t]);
                }
                if (fb.length() > 0) freeText = fb.toString();
            }

            List<String> resolved = new ArrayList<>();
            for (String label : labels) {
                if ("Other".equals(label)) {
                    if (freeText == null || freeText.trim().isEmpty()) return Optional.empty();
                    resolved.add("Other: " + freeText.trim());
                } else {
                    resolved.add(label);
                }
            }

            if (!sub.multiSelect() && resolved.size() > 1) return Optional.empty();
            selections.merge(String.valueOf(qIdx), resolved, (a, b) -> {
                List<String> combined = new ArrayList<>(a);
                combined.addAll(b);
                return combined;
            });
        }

        if (selections.isEmpty()) return Optional.empty();
        // 每题都必须答:selections.size == question.questions.size
        if (selections.size() != question.questions().size()) return Optional.empty();
        return Optional.of(new ChoiceAnswer(selections));
    }

    /**
     * 把连续字母(如 "AB" / "abc" / "E")按 option index 映射回 label 列表。
     * "Other" 位 = options.size() + 1 (A=0, B=1, C=2, D=3, Other=E=4)。
     *
     * @return 匹配到的 label 列表;有任一字母越界返 null
     */
    private static List<String> resolveLetters(String letters, ClarifyQuestion.SubQuestion sub) {
        int nOpts = sub.options().size();
        List<String> labels = new ArrayList<>();
        for (char c : letters.toUpperCase().toCharArray()) {
            int pos = c - 'A';
            if (pos < 0) return null;
            if (pos < nOpts) {
                labels.add(sub.options().get(pos).label());
            } else if (pos == nOpts) {
                // Other 位
                labels.add("Other");   // caller 会替换成 "Other: freeText"
            } else {
                return null;
            }
        }
        return labels;
    }
}
