package com.xilidou.jooj.prompt;

import java.util.List;

/**
 * SYSTEM prompt 组装的 context — s10。
 *
 * <p>不可变 record,字段顺序与 {@link SystemPromptAssembler} 缓存 key 序列化顺序绑定。
 * 添加新字段时须同步评估对 cache key 稳定性的影响(往后追加字段是兼容的,
 * 但顺序变化会 invalidates 所有 in-memory cache)。
 *
 * <p>对应 Python s10 的 {@code context} dict:
 * <pre>
 *   {"enabled_tools": [...], "workspace": "...", "memories": "...", "skills": "..."}
 * </pre>
 *
 * @param enabledTools  当前可用工具名列表(顺序敏感,与 ToolRegistry 收集顺序一致)
 * @param workspace     工作目录绝对路径
 * @param memoryCatalog MEMORY.md 索引内容,空字符串表示无 memory
 * @param skillCatalog  Skill catalog(name + 缩略 description),空字符串表示无 skill
 */
public record PromptContext(
        List<String> enabledTools,
        String workspace,
        String memoryCatalog,
        String skillCatalog
) {
}
