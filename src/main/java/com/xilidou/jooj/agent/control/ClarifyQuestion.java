package com.xilidou.jooj.agent.control;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * s22 AQ:agent 主动向用户提问的 {@link PendingQuestion} —— 对齐 Anthropic Claude Code SDK
 * 的 {@code AskUserQuestion} 特性,支持:
 *
 * <ul>
 *   <li>**1-4 个问题** 一起提(用户批量答复)</li>
 *   <li>每问 **2-4 个 options**(每 option 有 label + description)</li>
 *   <li>可 {@code multiSelect}(用户勾多个)</li>
 *   <li>{@code header} 短标签(≤12 字符)供前端渲染 chip/tag</li>
 * </ul>
 *
 * <h3>Vs PermissionQuestion</h3>
 *
 * <ul>
 *   <li>PermissionQuestion —— 系统触发(hook 判 ASK),问用户"是否允许 tool"</li>
 *   <li>ClarifyQuestion —— agent 主动触发(LLM 调 ask_user_question tool),问用户"这几个选项选哪个"</li>
 * </ul>
 *
 * <h3>为什么允许多问题</h3>
 *
 * <p>SDK 定义就是"1-4 个",场景:LLM 想让用户一次答完"用哪个 lib" + "什么 code style" +
 * "要不要写测试" 三个问题,弹一次框搞定。前端可以 collapse 或竖排渲染。
 *
 * <p>答复形态是 {@link ChoiceAnswer} —— 每 sub-question 一个 answer(index 或 label)。
 */
public record ClarifyQuestion(
        String askId,
        Instant askedAt,
        List<SubQuestion> questions
) implements PendingQuestion {

    public ClarifyQuestion {
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty() || questions.size() > 4) {
            throw new IllegalArgumentException("questions must have 1-4 entries, got: " + questions.size());
        }
    }

    @Override
    public String type() {
        return "clarify";
    }

    /**
     * 一个 sub-question。
     *
     * @param question    完整问句(必以问号结尾,SDK 规范)
     * @param header      12 字符内的短标签(chip/tag 用),不能空
     * @param options     2-4 个选项
     * @param multiSelect 是否允许多选(true 时 answer 是 List;false 时是单值)
     */
    public record SubQuestion(
            String question,
            String header,
            List<Option> options,
            boolean multiSelect
    ) {
        public SubQuestion {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question required");
            }
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException("header required");
            }
            if (header.length() > 12) {
                throw new IllegalArgumentException("header must be ≤12 chars, got: " + header);
            }
            Objects.requireNonNull(options, "options");
            if (options.size() < 2 || options.size() > 4) {
                throw new IllegalArgumentException("options must have 2-4 entries, got: " + options.size());
            }
        }
    }

    /**
     * 一个 option。
     *
     * @param label       用户看到的短选项文本(≤5 字)
     * @param description 解释 —— 选这个会发生什么 / trade-off,可空
     */
    public record Option(String label, String description) {
        public Option {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("label required");
            }
        }
    }

    /** 便利工厂 —— 生成 askId + askedAt,给 tool 使用。 */
    public static ClarifyQuestion of(List<SubQuestion> questions) {
        return new ClarifyQuestion(
                PendingQuestion.newAskId(),
                Instant.now(),
                questions);
    }
}
