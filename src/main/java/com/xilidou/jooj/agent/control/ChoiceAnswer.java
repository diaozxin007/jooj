package com.xilidou.jooj.agent.control;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * s22 AQ:用户对 {@link ClarifyQuestion} 的答复 —— 每个 sub-question 一个 answer entry。
 *
 * <p>key 是 sub-question 的 index(0-based,字符串形式便于 JSON serialize),
 * value 是**选中 option 的 label 列表**(single-select 时是 1 个,multi-select 时可多个)。
 *
 * <p>为什么用 label 而不是 index:label 是"用户看到的东西",LLM 拿回 answer 后可以直接
 * 塞进 prompt(比"用户选了第 2 项"更清晰)。前端 POST /answer 时同样发 label。
 *
 * <h3>典型 JSON 形态</h3>
 *
 * <pre>
 *   { "0": ["React"], "1": ["Yes", "Add tests"] }
 * </pre>
 *
 * <p>表示:第 0 个问题选了 "React";第 1 个问题(multiSelect)勾了 "Yes" 和 "Add tests"。
 */
public record ChoiceAnswer(Map<String, List<String>> selections) implements Answer {

    public ChoiceAnswer {
        Objects.requireNonNull(selections, "selections");
    }

    /** 便利:取 index=0 单值(最常见的单问题单选场景)。 */
    public String firstSingle() {
        List<String> v = selections.get("0");
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }
}
