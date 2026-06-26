package com.xilidou.marvis.team;

import com.xilidou.marvis.tasks.TaskRecord;
import com.xilidou.marvis.tasks.TaskService;
import com.xilidou.marvis.tasks.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Autonomous Idle —— s17 自组织队友的核心:**自己看板,自己认领**。
 *
 * <p>对应 Python 上游 s17 的 {@code scan_unclaimed_tasks} + auto-claim 逻辑。
 *
 * <h3>职责</h3>
 *
 * <ul>
 *   <li>{@link #scanAvailable} —— 扫 TaskBoard,返回可认领的 task(pending + 无 owner + blockedBy 已完成)</li>
 *   <li>{@link #tryClaim} —— 给 agent 派一个可认领的任务,返回任务摘要(成功)或 null(没活/被抢)</li>
 * </ul>
 *
 * <h3>跟 marvis 已有 s12 TaskService 的关系</h3>
 *
 * <p>s12 已经实现了 {@code list / canStart / claim},本类只是把它们组合成
 * "扫一遍可领的 → 拿第一个 → claim",**没造新轮子**。
 * 拆出独立 Component 让 Teammate.runLoop 只调一行就完事,业务逻辑不污染队友主循环。
 *
 * <h3>线程安全</h3>
 *
 * <p>{@link TaskService.claim} 内部已经做了"先读 status 再改"的检查 ——
 * 多个 teammate 并发 claim 同一个 task 时,第二个会拿到 "Cannot claim..." 字符串,
 * {@link #tryClaim} 把这个失败转成 {@code null} 返回。
 */
@Component
@Slf4j
public class AutonomousIdle {

    private final TaskService tasks;

    public AutonomousIdle(TaskService tasks) {
        this.tasks = tasks;
    }

    /**
     * 扫 TaskBoard 返回所有"可被某个 agent 认领"的 task。
     *
     * <p>条件(跟上游 s17 严格一致):
     * <ul>
     *   <li>status = PENDING</li>
     *   <li>owner = null(未被认领)</li>
     *   <li>blockedBy 全部已 COMPLETED({@link TaskService#canStart})</li>
     * </ul>
     *
     * @return 可认领的 task 列表(按 list 默认顺序,通常是 id / 创建时序);空 list 表示无活
     */
    public List<TaskRecord> scanAvailable() {
        return tasks.list().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .filter(t -> t.getOwner() == null || t.getOwner().isBlank())
                .filter(t -> tasks.canStart(t.getId()))
                .toList();
    }

    /**
     * 尝试给 {@code agentName} 派一个任务。
     *
     * <p>逻辑:
     * <ol>
     *   <li>scanAvailable → 没有可领的 task → 返 {@link Optional#empty()}</li>
     *   <li>取第一个 → claim → 成功返该 TaskRecord</li>
     *   <li>claim 失败(被抢)→ 继续扫下一个 → 否则空</li>
     * </ol>
     *
     * <p>**不会循环 retry**(教学版接受争抢失败) —— scanAvailable 一次,从中找到第一个能 claim 的就返。
     *
     * @param agentName claim 的 owner
     * @return 已 claim 的 task,或 empty 表示没活/全被抢
     */
    public Optional<TaskRecord> tryClaim(String agentName) {
        List<TaskRecord> candidates = scanAvailable();
        for (TaskRecord t : candidates) {
            String result = tasks.claim(t.getId(), agentName);
            if (result.startsWith("Claimed ")) {
                log.info("[Idle] {} auto-claimed task {} ({})",
                        agentName, t.getId(), t.getSubject());
                // 重新读一次拿到 in_progress 状态
                return tasks.get(t.getId());
            }
            // 被别的 agent 抢走或状态变了 —— 跳到下一个
            log.debug("[Idle] {} failed to claim {}: {}", agentName, t.getId(), result);
        }
        return Optional.empty();
    }
}
