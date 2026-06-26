package com.xilidou.marvis.tasks;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Task 业务封装 —— 把 {@link TaskStore} 文件 I/O 提升为业务语义,
 * 同时承载 {@code canStart / claim / complete + unblocked 扫描} 等关键逻辑。
 *
 * <p>对应 Python s12 的 {@code create_task / can_start / claim_task /
 * complete_task / list_tasks},严格对齐字段集 + 错误格式 + unblocked 算法。
 *
 * <h3>错误返回是字符串(NL),不抛异常</h3>
 *
 * <p>{@link #claim} / {@link #complete} 失败时返回**人类可读的字符串错误**
 * (如 {@code "Task X is in_progress, cannot claim"})。这是为了让 LLM 看到字符串后
 * **自我纠正**(读完报错就知道要先 list 看状态),而不是异常往上跑被工具层包装失败。
 * 跟上游严格一致。
 *
 * <h3>独立层,不耦合 Subagent / RecoveryCoordinator</h3>
 *
 * <p>本服务**只读写文件**,不调 LLM,不依赖 Subagent / Hook / Permission。
 * 跟上游 README 强调的"独立层,自然组合"一致 —— s06 task / s11 recovery /
 * s12 tasks 各自独立,工具层用 {@link com.xilidou.marvis.tool.impl.TasksTool}
 * 把这些组合起来给 LLM 看。
 */
@Slf4j
public class TaskService {

    /**
     * 单 agent loop 假设 —— claim_task 总用同一个 owner。跟 Python 的
     * {@code claim_task(task_id, owner="agent")} 默认值一致。
     */
    public static final String DEFAULT_OWNER = "agent";

    private final TaskStore store;

    public TaskService(TaskStore store) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.store = store;
    }

    // ─────────────────────────────────────────────────────────────
    //  Create / Read
    // ─────────────────────────────────────────────────────────────

    /**
     * 创建并落盘一条新 task。返回新分配的 id。
     *
     * <p>id 格式:{@code task_<unix_ts>_<rand4>},严格照搬 Python
     * {@code f"task_{int(time.time())}_{random.randint(0,9999):04d}"}。
     * collision 概率(同一秒内两条 task 撞 4 位随机数)= 1/10000 量级 ——
     * 教学版接受,marvis 是单进程 REPL 不会高频创建 task。
     */
    public String create(String subject, String description, List<String> blockedBy) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }

        TaskRecord t = new TaskRecord();
        t.setId(generateId());
        t.setSubject(subject);
        t.setDescription(description == null ? "" : description);
        t.setStatus(TaskStatus.PENDING);
        t.setOwner(null);
        t.setBlockedBy(blockedBy == null ? new ArrayList<>() : new ArrayList<>(blockedBy));

        store.write(t);
        log.info("[Tasks] created {} '{}' (blockedBy={})", t.getId(), subject, t.getBlockedBy());
        return t.getId();
    }

    public Optional<TaskRecord> get(String id) {
        return store.read(id);
    }

    /**
     * s18:保存 task 修改 —— 给 {@link com.xilidou.marvis.team.WorktreeService#bindTask} 用。
     *
     * <p>不做 status 校验,调用方自己确保字段合法(目前唯一调用点是 worktree 绑定,
     * 只改 {@code worktree} 字段,不影响状态机)。
     *
     * <p>未来若要增加更结构化的"字段更新"语义(setOwner / setBlockedBy / etc),
     * 可以加专门方法替代直接 save。
     */
    public void save(TaskRecord task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        store.write(task);
    }

    public List<TaskRecord> list() {
        return store.list();
    }

    // ─────────────────────────────────────────────────────────────
    //  Status transitions
    // ─────────────────────────────────────────────────────────────

    /**
     * 检查 task 的所有 {@code blockedBy} 依赖是否都到 {@link TaskStatus#COMPLETED}。
     *
     * <p>**Defensive 行为**:dep 文件不存在视为 blocked 而非异常 —— 跟上游 README
     * 强调的一致(LLM 可能传错 id,我们 fail soft 让它读回错误自己纠正)。
     *
     * @return true 表示可以 claim,false 表示有未完成依赖或本任务自己不存在
     */
    public boolean canStart(String id) {
        Optional<TaskRecord> taskOpt = store.read(id);
        if (taskOpt.isEmpty()) return false;
        TaskRecord task = taskOpt.get();
        if (task.getBlockedBy() == null) return true;
        for (String depId : task.getBlockedBy()) {
            if (!store.exists(depId)) return false;
            Optional<TaskRecord> dep = store.read(depId);
            if (dep.isEmpty() || dep.get().getStatus() != TaskStatus.COMPLETED) return false;
        }
        return true;
    }

    /**
     * 认领一条 PENDING task。
     *
     * <p>失败语义(NL 错误字符串):
     * <ul>
     *   <li>task 不存在 → {@code "Error: Task <id> not found"}</li>
     *   <li>状态不是 PENDING → {@code "Task <id> is <status>, cannot claim"}</li>
     *   <li>有未完成 dep → {@code "Blocked by: [<dep1>, <dep2>]"}</li>
     * </ul>
     *
     * @return 成功时 {@code "Claimed <id> (<subject>)"};失败时人类可读错误
     */
    public String claim(String id, String owner) {
        Optional<TaskRecord> taskOpt = store.read(id);
        if (taskOpt.isEmpty()) {
            return "Error: Task " + id + " not found";
        }
        TaskRecord task = taskOpt.get();
        if (task.getStatus() != TaskStatus.PENDING) {
            return "Task " + id + " is " + task.getStatus().getValue() + ", cannot claim";
        }
        if (!canStart(id)) {
            // 收集未完成的 dep id 列表(跟上游 Python 的 list comprehension 一致)
            List<String> blockingDeps = new ArrayList<>();
            for (String depId : task.getBlockedBy()) {
                if (!store.exists(depId)) {
                    blockingDeps.add(depId);
                    continue;
                }
                Optional<TaskRecord> dep = store.read(depId);
                if (dep.isEmpty() || dep.get().getStatus() != TaskStatus.COMPLETED) {
                    blockingDeps.add(depId);
                }
            }
            return "Blocked by: " + blockingDeps;
        }

        task.setOwner(owner == null ? DEFAULT_OWNER : owner);
        task.setStatus(TaskStatus.IN_PROGRESS);
        store.write(task);
        log.info("[Tasks] claimed {} → in_progress (owner={})", id, task.getOwner());
        return "Claimed " + task.getId() + " (" + task.getSubject() + ")";
    }

    /**
     * 完成一条 IN_PROGRESS task,扫描所有 task 找出新 unblocked 的。
     *
     * <p>**这是 s12 最聪明的设计** —— 完成 A 时立刻告诉 LLM "B 现在可以做了",
     * LLM 能直接 claim_task B 而不是 list_tasks 后再决策。少一轮 LLM 调用。
     *
     * <p>失败语义(NL 错误):
     * <ul>
     *   <li>task 不存在 → {@code "Error: Task <id> not found"}</li>
     *   <li>状态不是 IN_PROGRESS → {@code "Task <id> is <status>, cannot complete"}</li>
     * </ul>
     *
     * @return 成功时 {@code "Completed <id> (<subject>)"};
     *         有 unblocked 时追加 {@code "\nUnblocked: <s1>, <s2>"};
     *         失败时 NL 错误字符串
     */
    public String complete(String id) {
        Optional<TaskRecord> taskOpt = store.read(id);
        if (taskOpt.isEmpty()) {
            return "Error: Task " + id + " not found";
        }
        TaskRecord task = taskOpt.get();
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            return "Task " + id + " is " + task.getStatus().getValue() + ", cannot complete";
        }

        task.setStatus(TaskStatus.COMPLETED);
        store.write(task);
        log.info("[Tasks] completed {} ({})", id, task.getSubject());

        // 扫描:status=PENDING + 有 blockedBy + 现在 canStart() 的 task 列表
        // 跟 Python 严格一致:`[t.subject for t in list_tasks() if t.status=="pending" and t.blockedBy and can_start(t.id)]`
        List<String> unblocked = list().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .filter(t -> t.getBlockedBy() != null && !t.getBlockedBy().isEmpty())
                .filter(t -> canStart(t.getId()))
                .map(TaskRecord::getSubject)
                .collect(Collectors.toList());

        StringBuilder msg = new StringBuilder();
        msg.append("Completed ").append(task.getId())
                .append(" (").append(task.getSubject()).append(")");
        if (!unblocked.isEmpty()) {
            msg.append("\nUnblocked: ").append(String.join(", ", unblocked));
        }
        return msg.toString();
    }

    // ─────────────────────────────────────────────────────────────
    //  internals
    // ─────────────────────────────────────────────────────────────

    /**
     * 生成新 task id。包级可见,允许测试通过反射或子类化时观察。
     *
     * <p>格式:{@code task_<unix_seconds>_<4位随机数>}。
     */
    String generateId() {
        long ts = System.currentTimeMillis() / 1000L;
        int rand = ThreadLocalRandom.current().nextInt(10000);
        return String.format("task_%d_%04d", ts, rand);
    }
}
