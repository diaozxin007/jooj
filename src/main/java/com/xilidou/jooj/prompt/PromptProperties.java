package com.xilidou.jooj.prompt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SYSTEM prompt 片段模板(s10)的 yml → Java 桥接。
 *
 * <p>对应 Python {@code PROMPT_SECTIONS} 字典。每个字段是一段命名 prompt,
 * 由 {@link SystemPromptAssembler} 在运行期按 context 选取拼接。
 *
 * <p><b>哪些字段允许用户 override</b>:
 * <ul>
 *   <li>{@code identity} / {@code tools} — 用户在 yml 里可以覆盖,
 *       让 agent persona 可定制(例如改成"你是一个评测专家")</li>
 *   <li>{@code workspace} — 不暴露,自动从 {@code System.getProperty("user.dir")} 读,
 *       避免用户配错路径</li>
 *   <li>{@code memoryHeader} — 仅控制 memory section 的标题前缀,
 *       memory 正文由 {@link com.xilidou.jooj.memory.MemoryService} 提供</li>
 * </ul>
 *
 * <p>字段较多但均为 String 模板,无派生逻辑 —— 豁免三分法,直接由
 * {@link SystemPromptAssembler} 消费。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Prompt} 拆出,前缀 {@code jooj.prompt} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.prompt")
public class PromptProperties {

    /** identity section:agent 自我定位,通常一两句话。 */
    private String identity =
            "You are a coding agent. " +
            "Before starting any multi-step task, use todo_write to plan your steps. " +
            "Update task status as you go. Act, don't explain.";

    /**
     * tools section 的使用提示(追加在动态工具列表之后)。
     * 工具名列表由 {@link com.xilidou.jooj.tool.ToolRegistry} 动态生成,
     * 此字段只放"怎么用"的 hint。
     */
    private String toolsHint =
            "For slow ops (build/test/deploy/install), set bash.run_in_background=true " +
            "to keep working while it runs in the background.";

    /**
     * memory section 的标题前缀。memory 正文由 MemoryService.catalog() 提供,
     * 此前缀放在正文之前形成完整的 memory section。
     */
    private String memoryHeader = "Memory index (long-term knowledge from past sessions):";

    /**
     * skill section 的标题前缀。skill 正文由 SkillRegistry.catalog() 提供,
     * 此前缀放在正文之前形成完整的 skill section。
     *
     * <p>提示 LLM 怎么用:catalog 只列出 name + description,需要完整内容时
     * 调 {@code load_skill(name=...)} 工具。空 catalog 时整段 skill section 跳过。
     */
    private String skillsHeader =
            "Available skills (call load_skill(name=...) to load full content):";
}
