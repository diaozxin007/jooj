package com.xilidou.marvis.agent;

import com.xilidou.marvis.config.MarvisExecutors;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * BackgroundTaskManager —— s13 慢操作派 daemon 线程 + 通知注入。
 *
 * <p>对应上游 [s13_background_tasks/code.py] 的 4 个全局结构 + 4 个函数:
 * <ul>
 *   <li>{@code background_tasks: dict[str, dict]} → {@link #tasks}</li>
 *   <li>{@code background_results: dict[str, str]} → {@link #results}</li>
 *   <li>{@code background_lock: threading.Lock} → {@link #lock}</li>
 *   <li>{@code background_counter: int} → {@link #counter}</li>
 *   <li>{@code start_background_task} → {@link #start}</li>
 *   <li>{@code drain_completed_results} → {@link #drainNotifications}</li>
 *   <li>{@code is_slow_operation} → {@link #isSlowOperation}</li>
 *   <li>{@code should_run_background} → {@link #shouldRunBackground}</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 *
 * <ul>
 *   <li><b>裸 Thread 不用 ExecutorService</b> —— 跟上游一致;教学版严格对齐,
 *       不引入线程池配置 / 拒绝策略 / 优雅关闭等工程化复杂度。daemon 线程让 JVM
 *       退出时不会被 hang 住</li>
 *   <li><b>2 个 Map + 1 个 lock</b> —— 跟上游 4 个 dict + 1 个 threading.Lock 严格对应。
 *       {@link #tasks} 跟踪所有 bg task 元数据(命令 + 状态 + tool_use_id),
 *       {@link #results} 只在完成时填充,{@link #drainNotifications} drain 时清空</li>
 *   <li><b>{@code <task_notification>} 文本块格式</b> —— 跟上游 XML 格式严格一致,
 *       LLM 看到结构化文本好辨识"这是后台完成通知,不是新工具调用结果"</li>
 *   <li><b>关键词列表硬编码</b> —— 不开放成 yaml 配置,跟上游一致;后续若要换关键词
 *       直接改 {@link #SLOW_KEYWORDS}</li>
 *   <li><b>独立层,不接 Subagent / Recovery / Permission</b> —— Permission 检查仍在
 *       AgentLoopHarness 派 bg 之前,不进 bg 队列</li>
 * </ul>
 *
 * <h3>数据流</h3>
 *
 * <pre>
 *   LLM 调 bash(run_in_background=true) 或 bash + 慢操作关键词命中
 *      ↓
 *   AgentLoopHarness 调 {@link #start} 派 daemon thread,立即拿 bg_id placeholder
 *      ↓
 *   placeholder 作为 ToolResultBlock 加入 toolResults
 *      ↓
 *   下一轮 LLM 调用前,AgentLoopHarness 调 {@link #drainNotifications}
 *      ↓
 *   已完成的 bg task 转成 List<TextBlock>,跟 toolResults 合并到同一条 user message
 *      ↓
 *   LLM 在下一轮看到 <task_notification> 文本块,自然消费
 * </pre>
 */
@Component
@Slf4j
public class BackgroundTaskManager {

    /**
     * 慢操作关键词 —— 跟上游 s13 严格一致(install / build / test / deploy /
     * compile / docker build / pip install / npm install / cargo build / pytest / make)。
     *
     * <p>启发式触发:命令字符串包含其中任意一个,就把 bash 派到后台。
     * LLM 显式 {@code run_in_background=true} 优先级高于此启发式
     * (见 {@link #shouldRunBackground})。
     */
    static final Set<String> SLOW_KEYWORDS = Set.of(
            "install", "build", "test", "deploy", "compile",
            "docker build", "pip install", "npm install",
            "cargo build", "pytest", "make"
    );

    /** bg id 自增计数器,从 0 开始,格式化为 4 位(bg_0001 / bg_0002 ...)。 */
    private final AtomicInteger counter = new AtomicInteger(0);

    /** bg_id → bg task 元数据(状态机 running/completed,tool_use_id,命令)。 */
    private final Map<String, BgTask> tasks = new HashMap<>();

    /** bg_id → 完成后的 output 字符串。daemon thread 完成时写,drain 时清。 */
    private final Map<String, String> results = new HashMap<>();

    /**
     * 单 lock 保护 {@link #tasks} + {@link #results}。
     *
     * <p>跟上游 {@code background_lock = threading.Lock()} 严格一致 ——
     * 单 lock 保两个结构,绝不分离锁(分离锁会让 drain 看到一个结构里的状态
     * 与另一结构不一致)。
     */
    private final Object lock = new Object();

    /**
     * BG 慢工具池 —— Stage 3 拆出独立池。
     *
     * <p>由 {@link MarvisExecutors#marvisBgExecutor} 提供,跟 Teammate 的池**分开**:
     * bg 用 {@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy},
     * 池满时 caller 线程 inline 跑工具,等价于本次没派 bg —— LLM 仍能拿到工具结果,
     * 只是这一轮慢一点。
     *
     * <p>因此 {@link #start} 不会抛 {@link RejectedExecutionException},
     * caller(AgentLoopHarness)无需 try-catch。
     */
    private final ExecutorService workerExecutor;

    public BackgroundTaskManager(
            @Qualifier(MarvisExecutors.BG_BEAN) ExecutorService workerExecutor) {
        this.workerExecutor = workerExecutor;
    }

    // ─────────────────────────────────────────────────────────────
    //  API
    // ─────────────────────────────────────────────────────────────

    /**
     * 派一个后台任务,立即返回新分配的 bg_id。
     *
     * <p>对应上游 {@code start_background_task(tool_use_id, command, work)}:
     * <ol>
     *   <li>分配 bg_id 形如 {@code bg_0001}</li>
     *   <li>记入 {@link #tasks}(状态 running)</li>
     *   <li>派 daemon thread 跑 {@code work},完成后落盘到 {@link #results}
     *       并把 {@link #tasks} 状态改为 completed</li>
     *   <li>立即返回 bg_id 给调用方,不等 work 跑完</li>
     * </ol>
     *
     * <p><b>异常处理</b>:work 抛异常时 status 仍变 completed,output 形如
     * {@code "Error: <message>"};LLM 在 task_notification 里仍能看到失败原因。
     * 跟上游一致 —— 不让异常逃逸出后台线程,避免 daemon thread 静默退出。
     *
     * @param toolUseId 触发此 bg 任务的 tool_use_id(只用作元数据,目前未读取)
     * @param command   命令字符串(只用作元数据 + log)
     * @param work      实际执行,daemon thread 调它,返回 ToolResult
     * @return 新分配的 bg_id 形如 {@code bg_0001}
     */
    public String start(String toolUseId, String command, Supplier<ToolResult> work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        // 跟上游一致:counter 递增 → bg_<4位>
        int n = counter.incrementAndGet();
        String bgId = String.format("bg_%04d", n);

        synchronized (lock) {
            tasks.put(bgId, new BgTask(toolUseId, command, "running"));
        }
        log.info("[BG] started {} for command: {}", bgId, command);

        // BG 池用 CallerRunsPolicy:满则 caller 线程同步跑,不抛异常,
        // 这里直接 submit 不需要 try-catch。降级语义对 LLM 透明 ——
        // 它只会感觉这一轮工具调用慢一点(变成同步)。
        workerExecutor.submit(() -> {
            String output;
            try {
                ToolResult r = work.get();
                output = r != null && r.getOutput() != null ? r.getOutput() : "(no output)";
            } catch (Exception e) {
                output = "Error: " + e.getMessage();
                log.warn("[BG] {} failed: {}", bgId, e.toString());
            }
            synchronized (lock) {
                results.put(bgId, output);
                BgTask cur = tasks.get(bgId);
                if (cur != null) {
                    tasks.put(bgId, new BgTask(cur.toolUseId(), cur.command(), "completed"));
                }
            }
            log.info("[BG] {} completed", bgId);
        });

        return bgId;
    }

    /**
     * 把所有已完成的 bg result drain 出来,转成 {@code <task_notification>} 文本块列表。
     *
     * <p>对应上游 {@code drain_completed_results}:
     * <ul>
     *   <li>扫所有 {@link #results} 条目,对应的 task 转成 TextBlock</li>
     *   <li>drain 之后清掉 results 里这些条目(跟上游 {@code del background_results[bg_id]} 一致)</li>
     *   <li>{@link #tasks} 里 completed 的条目也一并清掉,避免 dict 长期膨胀</li>
     * </ul>
     *
     * <p><b>幂等</b>:同一个 bg_id 只能被 drain 一次。再次调 drain 时该 bg_id 已不在
     * results 里,不会重复返回。
     *
     * @return List<TextBlock>,空表示当前无已完成 bg task。返回顺序按 bg_id 字典序
     *         (= 时间序,bg_id 自增分配)
     */
    public List<TextBlock> drainNotifications() {
        List<TextBlock> blocks = new ArrayList<>();
        synchronized (lock) {
            // 拷贝 keys 避免 ConcurrentModificationException(removeAll 在循环里改 map)
            List<String> drainable = new ArrayList<>(results.keySet());
            // bg_id 字典序 = 时间序(bg_0001 < bg_0002 < ...),让 LLM 按发起顺序看通知
            drainable.sort(String::compareTo);
            for (String bgId : drainable) {
                String output = results.remove(bgId);
                BgTask task = tasks.remove(bgId);
                String cmd = task != null ? task.command() : "(unknown)";
                String text = String.format(
                        "<task_notification id=\"%s\" command=\"%s\">%n%s%n</task_notification>",
                        bgId,
                        escapeAttribute(cmd),
                        output
                );
                blocks.add(new TextBlock(text));
            }
        }
        return blocks;
    }

    /**
     * 慢操作启发式 —— 跟上游 {@code is_slow_operation} 严格一致。
     *
     * <p>逻辑:工具名是 bash AND 命令字符串包含 {@link #SLOW_KEYWORDS} 任意一个。
     * 非 bash 工具(read_file / write_file / glob 等)永不命中启发式 —— 这些工具天然就快。
     *
     * <p>注意 {@code build} 这种关键词比较激进:命中后 bash 命令会被派到后台。教学版
     * 严格对齐上游;若误判想关掉,改 {@link #SLOW_KEYWORDS}。
     *
     * @param toolName 工具名,如 {@code "bash"}
     * @param args     工具参数,bash 工具应有 {@code "command"} 键
     * @return true 表示命中启发式
     */
    public static boolean isSlowOperation(String toolName, Map<String, Object> args) {
        if (!"bash".equals(toolName)) return false;
        if (args == null) return false;
        Object cmd = args.get("command");
        if (cmd == null) return false;
        String s = cmd.toString();
        for (String kw : SLOW_KEYWORDS) {
            if (s.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 是否应该走后台 —— 跟上游 {@code should_run_background} 严格一致。
     *
     * <p>决策顺序:
     * <ol>
     *   <li>LLM 显式 {@code run_in_background=true} → 走后台(优先)</li>
     *   <li>否则若 {@link #isSlowOperation} 命中 → 走后台</li>
     *   <li>否则同步执行</li>
     * </ol>
     *
     * <p>布尔参数兼容:LLM 在 input 里给 {@code true} (Boolean) 或 {@code "true"} (String)
     * 都识别 —— 教学版宽松,生产化可以严格一些。
     *
     * @param toolName 工具名
     * @param args     工具参数
     * @return true 表示走后台
     */
    public static boolean shouldRunBackground(String toolName, Map<String, Object> args) {
        if (args != null) {
            Object explicit = args.get("run_in_background");
            if (explicit instanceof Boolean b && b) return true;
            if (explicit instanceof String s && "true".equalsIgnoreCase(s)) return true;
        }
        return isSlowOperation(toolName, args);
    }

    // ─────────────────────────────────────────────────────────────
    //  test 钩子
    // ─────────────────────────────────────────────────────────────

    /** 测试用:当前 running / completed task 总数。 */
    int taskCount() {
        synchronized (lock) {
            return tasks.size();
        }
    }

    /** 测试用:当前未 drain 的已完成 result 数。 */
    int pendingResultCount() {
        synchronized (lock) {
            return results.size();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────────────────────

    /** XML attribute 简易转义,只处理双引号 + 换行,跟上游 Python 格式一致。 */
    private static String escapeAttribute(String s) {
        if (s == null) return "";
        return s.replace("\"", "&quot;").replace("\n", " ").replace("\r", " ");
    }

    /**
     * bg task 元数据 record。{@code status} 是 {@code "running"} 或 {@code "completed"}
     * 字符串(跟上游一致,不抽 enum 是为了对齐 Python dataclass 的 string 字段)。
     */
    record BgTask(String toolUseId, String command, String status) {}
}
