package com.xilidou.marvis.tasks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 单条记录 —— 严格对齐上游 s12 6 字段 dataclass。
 *
 * <p>对应 Python:
 * <pre>
 *   @dataclass
 *   class Task:
 *       id: str
 *       subject: str
 *       description: str
 *       status: str          # pending | in_progress | completed
 *       owner: str | None
 *       blockedBy: list[str]
 * </pre>
 *
 * <h3>刻意省略的字段(对齐上游)</h3>
 *
 * <ul>
 *   <li>{@code result} —— 上游就没有,留给后续真 CC port 扩展</li>
 *   <li>{@code blocks}(反向依赖)—— 用时遍历整个 list 查 blockedBy</li>
 *   <li>{@code createdAt} / {@code updatedAt} —— 上游无,id 里已有 unix timestamp</li>
 *   <li>{@code activeForm} / {@code metadata} / {@code comments} —— 这些都是
 *       marvis 工程化扩展会想加的"漂亮东西",但 s12 教学版不要</li>
 * </ul>
 *
 * <p>Jackson 反序列化兼容:
 * <ul>
 *   <li>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 防止后续加字段时崩</li>
 *   <li>{@code blockedBy} 默认 {@link ArrayList}(避免 null)</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskRecord {

    /** 任务 ID,形如 {@code task_<unix_ts>_<rand4>}。也用作文件名(.json 前缀)。 */
    private String id;

    /** 任务标题(必填,LLM 给 create_task 时传)。 */
    private String subject;

    /** 详细描述,默认空串(create_task 可省略)。 */
    private String description = "";

    /** 任务状态,3 态。 */
    private TaskStatus status;

    /**
     * 当前认领人。{@code null} 直到 {@code claim_task} 被调用。
     *
     * <p>marvis 是单 agent loop,这个字段实际上恒为 {@code "agent"} 或 {@code null};
     * 保留它纯粹为对齐上游字段集,方便后续多 agent 扩展。
     */
    private String owner;

    /**
     * 阻塞依赖的 task ID 列表。所有 dep 必须先到 {@link TaskStatus#COMPLETED} 才能 claim 自己。
     *
     * <p>默认 {@link ArrayList} 而非 {@link List#of()},因为后者不可变,Jackson 反序列化时不便。
     */
    private List<String> blockedBy = new ArrayList<>();
}
