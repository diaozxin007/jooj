package com.xilidou.jooj.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.skill.SkillRegistry;
import com.xilidou.jooj.tool.ToolRegistry;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.http.dto.CacheControl;
import com.xilidou.jooj.http.dto.SystemTextBlock;
import com.xilidou.jooj.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SystemPromptAssembler — s10:运行期 SYSTEM prompt 片段化组装 + 缓存。
 *
 * <p>对应 Python s10 的 {@code PROMPT_SECTIONS} + {@code assemble_system_prompt} +
 * {@code get_system_prompt} 三件套。
 *
 * <h3>解决的问题</h3>
 *
 * <p>切片 C 完成后,jooj 的 SYSTEM 是 {@code @PostConstruct} 一次性算好的快照,
 * 中途新写入的 memory(由 {@link MemoryService#onTurnEnd} 持久化)永远进不了
 * SYSTEM 的"模型自知有哪些 memory"视图。s10 的运行期组装恰好修这个 bug。
 *
 * <h3>四个核心信条</h3>
 *
 * <ul>
 *   <li><b>片段化模板</b>:section 是命名条目(identity/tools/workspace/memory),
 *       而非散落字符串</li>
 *   <li><b>运行期组装</b>:每轮 LLM 调用前由 AgentLoopHarness 调
 *       {@link #assemble(PromptContext)} 拼接,新 memory 立刻可见</li>
 *   <li><b>稳定缓存</b>:context 不变时直接返回上次结果。key 用
 *       {@link ObjectMapper#writeValueAsString} 而非 Java {@code hashCode()},
 *       deterministic 跨 JVM 实例一致(对应 Python {@code json.dumps(sort_keys=True)})</li>
 *   <li><b>真实状态驱动</b>:{@link #currentContext()} 每次从 {@link ToolRegistry} /
 *       {@link MemoryService} 实时读出当前状态,不是关键词触发</li>
 * </ul>
 *
 * <h3>section 顺序</h3>
 *
 * <p>identity → tools → workspace → memory(空 section 跳过)。
 * 顺序固定 {@link #SECTION_ORDER},保证 SYSTEM 字符串 deterministic,
 * 利于上游(如 Anthropic prompt cache)命中。
 *
 * <h3>线程安全</h3>
 *
 * <p>缓存字段用 {@code volatile} 保护可见性。当前 jooj Loop 是单线程,
 * 但 future Week 8 后台任务、Week 9 多 Agent 会有并发,这里提前打底。
 * 无锁设计:race 时多算一次 prompt(不致命),最终一致。
 */
@Component
@Slf4j
public class SystemPromptAssembler {

    /** section 拼接顺序。 */
    private static final List<String> SECTION_ORDER =
            List.of("identity", "tools", "workspace", "skills", "memory");

    /** section 之间的分隔符,与 Python 版一致。 */
    private static final String SECTION_DELIMITER = "\n\n";

    private final PromptProperties template;
    private final ObjectMapper json;
    private final ToolRegistry registry;
    private final MemoryService memoryService;
    private final SkillRegistry skillRegistry;
    private final String workspace;

    // ── 缓存(单 entry,Spring 单例)──────────────────────────────
    private volatile String cachedKey;
    private volatile String cachedPrompt;

    public SystemPromptAssembler(PromptProperties template,
                                 @Qualifier("joojObjectMapper") ObjectMapper json,
                                 ToolRegistry registry,
                                 MemoryService memoryService,
                                 SkillRegistry skillRegistry) {
        this.template = template;
        this.json = json;
        this.registry = registry;
        this.memoryService = memoryService;
        this.skillRegistry = skillRegistry;
        // workspace 启动时就固定,不暴露 yml override 防止用户配错
        this.workspace = System.getProperty("user.dir");
    }

    /**
     * 从当前真实状态构造 {@link PromptContext}:
     * <ul>
     *   <li>{@code enabledTools} ← {@link ToolRegistry#getAllTools()}</li>
     *   <li>{@code workspace} ← cwd(启动时固定)</li>
     *   <li>{@code memoryCatalog} ← {@link MemoryService#catalog()}</li>
     *   <li>{@code skillCatalog} ← {@link SkillRegistry#catalog()}</li>
     * </ul>
     *
     * <p>每轮 LLM 调用前由 AgentLoopHarness 调用,确保 context 反映**当前**状态
     * (尤其是 memory:turn 1 写的 memory,turn 2 立刻可见;skill 启动后不变,但放这里
     * 让 cache key 一致。)
     */
    public PromptContext currentContext() {
        List<String> toolNames = new ArrayList<>();
        for (ToolDefinition def : registry.getAllTools()) {
            toolNames.add(def.getName());
        }
        // 触发 SkillRegistry 节流式重扫:让会话中通过 bash 装的新 skill 下一轮 turn
        // 自动可见,不需要重启 jooj。force=false → 1s 内重复调用 no-op,IO 安全。
        skillRegistry.rescan(false);
        return new PromptContext(toolNames, workspace,
                memoryService.catalogForSystemPrompt(), skillRegistry.catalog());
    }

    /**
     * 按 context 组装 SYSTEM prompt。命中缓存时直接返回。
     *
     * <p>section 顺序:{@link #SECTION_ORDER}。空 section(如 memoryCatalog 为空时)
     * 跳过,不留 trailing delimiter。
     */
    public String assemble(PromptContext ctx) {
        String key = cacheKey(ctx);
        if (key != null && key.equals(cachedKey) && cachedPrompt != null) {
            log.debug("[Prompt] cache hit");
            return cachedPrompt;
        }

        StringBuilder sb = new StringBuilder();
        List<String> loaded = new ArrayList<>();
        for (String section : SECTION_ORDER) {
            String content = sectionContent(section, ctx);
            if (content == null || content.isBlank()) continue;
            if (sb.length() > 0) sb.append(SECTION_DELIMITER);
            sb.append(content);
            loaded.add(section);
        }

        String prompt = sb.toString();
        log.info("[Prompt] assembled, sections: {}", loaded);

        if (key != null) {
            this.cachedKey = key;
            this.cachedPrompt = prompt;
        }
        return prompt;
    }

    /**
     * 按 context 组装 SYSTEM,**输出两段 text block,启用 Anthropic prompt cache**:
     * <ol>
     *   <li><b>Block 1(稳定段)</b>:identity + tools + workspace。
     *       这三段进程启动后永远不变 → 末尾打 {@code cache_control: ephemeral}</li>
     *   <li><b>Block 2(易变段)</b>:memory header + memoryCatalog。
     *       中途新写入 memory 会变 → 不打 cache_control</li>
     * </ol>
     *
     * <p>命中时 Block 1 跳过 prefill,只 prefill Block 2 + messages。memory 写入
     * 时,**Block 1 仍命中**,只有 Block 2 重写。
     *
     * <p>memoryCatalog 为空时,只返回 Block 1(整段都加缓存)。
     *
     * <p><b>当前阈值警告</b>:Sonnet 4.6 ≥ 2048 token / Opus 4.6+ ≥ 4096 token
     * 才能命中。jooj 现在 Block 1 仅 ~500 token,Anthropic 会**静默忽略**
     * cache_control(不报错但 cache_creation_input_tokens 永远是 0)。
     * 此实现是结构准备 —— SYSTEM 扩到 4K+ 时立刻生效,届时单条会话能省 80%+ 输入开销。
     *
     * @param ctx 当前 context(由 {@link #currentContext()} 实时构造)
     * @return 1 或 2 个 text block,适合直接放入 {@code CreateMessageRequest.system}
     */
    public List<SystemTextBlock> assembleBlocks(PromptContext ctx) {
        // 第 1 段:稳定内容(identity + tools + workspace + skills)
        // skills 放进稳定段:启动后 SkillRegistry 是 immutable,跟 identity/tools 一档
        StringBuilder stable = new StringBuilder();
        appendSection(stable, sectionContent("identity", ctx));
        appendSection(stable, sectionContent("tools", ctx));
        appendSection(stable, sectionContent("workspace", ctx));
        appendSection(stable, sectionContent("skills", ctx));

        SystemTextBlock stableBlock = SystemTextBlock.builder()
                .type("text")
                .text(stable.toString())
                .cacheControl(CacheControl.ephemeral())
                .build();

        // 第 2 段:易变内容(memory)。空则不加这一段
        String memorySection = sectionContent("memory", ctx);
        if (memorySection == null || memorySection.isBlank()) {
            log.debug("[Prompt] assembled 1 block (no memory)");
            return List.of(stableBlock);
        }

        SystemTextBlock memoryBlock = SystemTextBlock.builder()
                .type("text")
                .text(memorySection)
                .build();   // 不加 cache_control

        log.debug("[Prompt] assembled 2 blocks (stable + memory)");
        return List.of(stableBlock, memoryBlock);
    }

    /** 把一段 section 内容追加到 StringBuilder,中间补分隔符。 */
    private void appendSection(StringBuilder sb, String content) {
        if (content == null || content.isBlank()) return;
        if (sb.length() > 0) sb.append(SECTION_DELIMITER);
        sb.append(content);
    }

    /**
     * 取一个 section 的拼好内容(含可能的 header)。
     *
     * <ul>
     *   <li>identity / tools — 直接用 {@link PromptProperties} 的模板</li>
     *   <li>workspace — {@code "Working directory: <cwd>"}</li>
     *   <li>skills — header + skillCatalog 正文(catalog 为空则整段跳过)</li>
     *   <li>memory — header + memoryCatalog 正文(catalog 为空则整段跳过)</li>
     * </ul>
     */
    private String sectionContent(String section, PromptContext ctx) {
        return switch (section) {
            case "identity" -> template.getIdentity();
            case "tools" -> buildToolsSection(ctx);
            case "workspace" -> "Working directory: " + ctx.workspace();
            case "skills" -> {
                String catalog = ctx.skillCatalog();
                if (catalog == null || catalog.isBlank()) yield null;
                yield template.getSkillsHeader() + "\n" + catalog;
            }
            case "memory" -> {
                String catalog = ctx.memoryCatalog();
                if (catalog == null || catalog.isBlank()) yield null;
                yield template.getMemoryHeader() + "\n" + catalog;
            }
            default -> null;
        };
    }

    /**
     * 动态生成 tools section:从 {@link PromptContext#enabledTools()} 拿当前
     * 真实注册的工具名,拼成 "Available tools: a, b, c. <hint>"。
     *
     * <p>好处:新加 Tool / 动态加载 MCP tool 后,system prompt 自动反映,
     * 不需要手工同步硬编码字符串。
     */
    private String buildToolsSection(PromptContext ctx) {
        List<String> tools = ctx.enabledTools();
        if (tools == null || tools.isEmpty()) {
            return template.getToolsHint();
        }
        return "Available tools: " + String.join(", ", tools) + ". " +
                template.getToolsHint();
    }

    /**
     * 计算稳定 cache key。Jackson 序列化保证字段顺序一致(对应 Python
     * {@code json.dumps(sort_keys=True)})。
     *
     * <p>序列化失败时返回 null —— 退化为不缓存,但保证组装仍能进行。
     */
    private String cacheKey(PromptContext ctx) {
        try {
            return json.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            log.warn("[Prompt] cache key serialization failed, falling back to no-cache: {}",
                    e.getMessage());
            return null;
        }
    }
}
