package com.xilidou.jooj.web;

import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.cron.CronJob;
import com.xilidou.jooj.cron.CronService;
import com.xilidou.jooj.memory.MemoryService;
import com.xilidou.jooj.skill.SkillRegistry;
import com.xilidou.jooj.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 给 Web UI 的 sidebar 用的只读 API —— 把 jooj 内部状态以 JSON 暴露给前端。
 *
 * <h3>Endpoint 一览</h3>
 *
 * <ul>
 *   <li>{@code GET /api/skills}  → 列出所有 skill 概要(name + description),供 sidebar Skills panel 用</li>
 *   <li>{@code GET /api/memory}  → memory catalog 字符串,供 sidebar Memory panel 用</li>
 *   <li>{@code GET /api/status}  → 运行时状态(model / cwd / 工具数 / cron / skill / memory 计数)</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 *
 * <ul>
 *   <li><b>只读</b> —— 不改变 agent 状态,不抢 {@code agentLock},可与 chat 并发</li>
 *   <li><b>轻量</b> —— 不重算,直接读已有 Bean 缓存的状态</li>
 *   <li><b>结构稳定</b> —— DTO 用 record,字段加新的安全(前端不会因为多字段崩),
 *       删字段是 breaking change</li>
 *   <li><b>分文件</b> —— 跟 {@link ChatController} 区分关注点,sidebar 加新 panel 时改这里就好</li>
 * </ul>
 *
 * <h3>不做的事</h3>
 *
 * <ul>
 *   <li>不暴露 skill body —— 几千 token 不适合塞前端,真要看 body 就调 {@code load_skill} 工具</li>
 *   <li>不做分页 —— v1 假设 skill / memory 都不会破百</li>
 *   <li>不做 SSE / 推送 —— 前端用户主动刷新 sidebar 即可</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class SidebarController {

    private final SkillRegistry skillRegistry;
    private final MemoryService memoryService;
    private final ToolRegistry toolRegistry;
    private final CronService cronService;
    private final JoojProperties props;

    public SidebarController(SkillRegistry skillRegistry,
                             MemoryService memoryService,
                             ToolRegistry toolRegistry,
                             CronService cronService,
                             JoojProperties props) {
        this.skillRegistry = skillRegistry;
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
        this.cronService = cronService;
        this.props = props;
    }

    /**
     * 列 skill 概要。前端 Skills panel 渲染 name + 截断的 description 列表。
     */
    @GetMapping("/skills")
    public SkillsResponse skills() {
        List<SkillSummary> summaries = skillRegistry.listSummaries().stream()
                .map(e -> new SkillSummary(e.getKey(), e.getValue()))
                .toList();
        return new SkillsResponse(summaries.size(), summaries);
    }

    /**
     * 强制重扫 skill 目录,刷新内存 registry,然后返回新概要。
     *
     * <p>用户在 sidebar 点 ↻ 时调。{@code force=true} 跳过 1s 节流,确保用户
     * 在 terminal 刚装的 skill 立刻可见。
     *
     * <p>POST 而非 GET 是因为这有 side effect(写 in-memory state、触发 IO)。
     */
    @PostMapping("/skills/rescan")
    public SkillsResponse skillsRescan() {
        skillRegistry.rescan(true);
        return skills();
    }

    /**
     * memory catalog 内容。空 catalog 时 {@code catalog} 字段为 ""。
     *
     * <p>前端 Memory panel 把 catalog 直接渲染成 Markdown(jooj memory 本来就是 .md 内容)。
     */
    @GetMapping("/memory")
    public MemoryResponse memory() {
        String catalog = memoryService.catalog();
        return new MemoryResponse(catalog == null ? "" : catalog);
    }

    /**
     * 运行时状态。前端 Status panel 平铺显示。
     *
     * <p>各计数:
     * <ul>
     *   <li>{@code toolCount} ← {@link ToolRegistry#getAllTools()} 大小</li>
     *   <li>{@code skillCount} ← {@link SkillRegistry#size()}</li>
     *   <li>{@code cronJobCount} ← {@link CronService#list()} 大小</li>
     *   <li>{@code memoryCharCount} ← memory catalog 字符数(粗略反映 memory 量)</li>
     * </ul>
     */
    @GetMapping("/status")
    public StatusResponse status() {
        String model = props.getAnthropic() == null ? "(unknown)" : props.getAnthropic().getModel();
        String workspace = System.getProperty("user.dir");

        int toolCount = toolRegistry.getAllTools().size();
        int skillCount = skillRegistry.size();
        int cronJobCount = 0;
        try {
            List<CronJob> jobs = cronService.list();
            cronJobCount = jobs == null ? 0 : jobs.size();
        } catch (Exception e) {
            // CronService 启动期可能还没 ready,容错
            log.debug("CronService.list() failed in /api/status: {}", e.getMessage());
        }
        int memoryCharCount = memoryService.catalog() == null ? 0
                : memoryService.catalog().length();

        return new StatusResponse(
                model == null ? "" : model,
                workspace,
                toolCount,
                skillCount,
                cronJobCount,
                memoryCharCount
        );
    }

    // ── DTOs ────────────────────────────────────────────────────

    /** {@link #skills} 响应:总数 + skill 概要列表。 */
    public record SkillsResponse(int total, List<SkillSummary> skills) {
    }

    /** 单个 skill 的概要 —— 不含 body,只够前端列表展示 + 用户决定是否在 chat 里 load。 */
    public record SkillSummary(String name, String description) {
    }

    /** {@link #memory} 响应:整个 catalog 一坨字符串(已是 Markdown)。 */
    public record MemoryResponse(String catalog) {
    }

    /** {@link #status} 响应:平铺式 key-value(前端按 label 显示)。 */
    public record StatusResponse(
            String model,
            String workspace,
            int toolCount,
            int skillCount,
            int cronJobCount,
            int memoryCharCount
    ) {
    }
}
