package com.xilidou.jooj.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.tasks.TaskRecord;
import com.xilidou.jooj.tasks.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Worktree 管理服务 —— s18 各干各的目录。
 *
 * <p>对应上游 {@code create_worktree / remove_worktree / keep_worktree /
 * bind_task_to_worktree / validate_worktree_name / log_event}。
 *
 * <h3>职责</h3>
 *
 * <ul>
 *   <li>{@link #create} —— git worktree add + 可选绑定 task + log create event</li>
 *   <li>{@link #remove} —— 安全检查未提交改动 + git worktree remove + git branch -D + log remove</li>
 *   <li>{@link #keep} —— 留 worktree 给人工 review,只 log keep event</li>
 *   <li>{@link #bindTask} —— 把 task.worktree 字段设上(不改 status)</li>
 *   <li>{@link #pathFor} —— 给 worktree name 返回文件系统路径,Teammate 切 cwd 用</li>
 *   <li>{@link #validateName} —— 拒绝 path traversal / 非法字符</li>
 * </ul>
 *
 * <h3>事件审计</h3>
 *
 * <p>每次 create / remove / keep 写一行 JSON 到 {@code <worktreeDir>/events.jsonl}。
 * 教学版只记录,不读;生产化可加 index / 状态恢复。
 */
@Component
@Slf4j
public class WorktreeService {

    /** name 校验:1-64 字符,字母数字加 . _ -。跟上游 {@code [A-Za-z0-9._-]{1,64}} 一致。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final Path worktreeDir;
    private final Path repoRoot;
    private final TaskService taskService;
    private final GitClient git;
    private final ObjectMapper json;

    public WorktreeService(JoojProperties props,
                           TaskService taskService,
                           GitClient git,
                           @Qualifier("joojObjectMapper") ObjectMapper json) {
        this.repoRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        this.worktreeDir = resolveWorktreeDir(props.getTeam().getWorktreeDir(), this.repoRoot);
        this.taskService = taskService;
        this.git = git;
        this.json = json;
    }

    private static Path resolveWorktreeDir(String configured, Path repoRoot) {
        Path p = Paths.get(configured);
        if (!p.isAbsolute()) p = repoRoot.resolve(p);
        return p.toAbsolutePath().normalize();
    }

    /**
     * 校验 worktree name 合法性。
     *
     * @return null 表示合法,非 null 是错误描述
     */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) return "Worktree name cannot be empty";
        if (".".equals(name) || "..".equals(name)) return "'" + name + "' is not a valid worktree name";
        if (!VALID_NAME.matcher(name).matches()) {
            return "Invalid worktree name '" + name +
                    "': only letters, digits, dots, underscores, dashes (1-64 chars)";
        }
        return null;
    }

    /** worktree name → 文件系统路径(可能不存在)。 */
    public Path pathFor(String name) {
        return worktreeDir.resolve(name);
    }

    /** worktree 根目录(测试 / 调试用)。 */
    public Path worktreesDir() {
        return worktreeDir;
    }

    // ─────────────────────────────────────────────────────────────
    //  Create
    // ─────────────────────────────────────────────────────────────

    /**
     * 创建一个 git worktree。
     *
     * <p>对应上游 {@code create_worktree(name, task_id?)}:
     * <ol>
     *   <li>name 校验</li>
     *   <li>worktree 路径已存在 → 失败</li>
     *   <li>{@code git worktree add <path> -b wt/<name> HEAD}</li>
     *   <li>taskId 非空时 bind</li>
     *   <li>log "create" event</li>
     * </ol>
     *
     * @return 成功时 {@code "Worktree '<name>' created at <path>"} / 失败时 {@code "Error: ..."}
     */
    public String create(String name, String taskId) {
        String err = validateName(name);
        if (err != null) return "Error: " + err;

        Path path = worktreeDir.resolve(name);
        if (Files.exists(path)) {
            return "Error: Worktree '" + name + "' already exists at " + path;
        }
        try {
            Files.createDirectories(worktreeDir);
        } catch (IOException e) {
            return "Error: failed to create worktree dir: " + e.getMessage();
        }

        GitClient.GitResult r = git.run(repoRoot, List.of(
                "worktree", "add", path.toString(), "-b", "wt/" + name, "HEAD"));
        if (!r.isSuccess()) {
            return "Error: git worktree add failed: " + r.getOutput();
        }

        if (taskId != null && !taskId.isBlank()) {
            String bindErr = bindTask(taskId, name);
            if (bindErr != null) {
                log.warn("[Worktree] created but bind failed: {}", bindErr);
            }
        }

        logEvent("create", name, taskId);
        log.info("[Worktree] created {} at {}", name, path);
        return "Worktree '" + name + "' created at " + path;
    }

    // ─────────────────────────────────────────────────────────────
    //  Bind
    // ─────────────────────────────────────────────────────────────

    /**
     * 把 task.worktree 字段设为 worktreeName(不改 status)。
     *
     * @return null 表示成功,非 null 是错误字符串
     */
    public String bindTask(String taskId, String worktreeName) {
        String err = validateName(worktreeName);
        if (err != null) return err;
        java.util.Optional<TaskRecord> opt = taskService.get(taskId);
        if (opt.isEmpty()) {
            return "Task " + taskId + " not found";
        }
        TaskRecord task = opt.get();
        task.setWorktree(worktreeName);
        taskService.save(task);
        log.info("[Worktree] bound task {} → worktree:{}", taskId, worktreeName);
        return null;   // null = 成功
    }

    // ─────────────────────────────────────────────────────────────
    //  Remove
    // ─────────────────────────────────────────────────────────────

    /**
     * 删除 worktree。
     *
     * <p>跟上游 {@code remove_worktree(name, discard_changes?)} 一致:
     * <ol>
     *   <li>name 校验</li>
     *   <li>路径不存在 → 失败</li>
     *   <li>{@code !discard_changes} 时检查未提交改动 + 未推送 commit,有就拒绝</li>
     *   <li>{@code git worktree remove <path> --force}</li>
     *   <li>{@code git branch -D wt/<name>}</li>
     *   <li>log "remove" event</li>
     * </ol>
     */
    public String remove(String name, boolean discardChanges) {
        String err = validateName(name);
        if (err != null) return "Error: " + err;

        Path path = worktreeDir.resolve(name);
        if (!Files.exists(path)) return "Error: Worktree '" + name + "' not found";

        if (!discardChanges) {
            int[] counts = countWorktreeChanges(path);
            int files = counts[0];
            int commits = counts[1];
            if (files < 0) {
                return "Error: Cannot verify worktree '" + name + "' status. " +
                        "Use discard_changes=true to force removal.";
            }
            if (files > 0 || commits > 0) {
                return "Error: Worktree '" + name + "' has " + files +
                        " uncommitted file(s) and " + commits + " unpushed commit(s). " +
                        "Use discard_changes=true to force removal, " +
                        "or keep_worktree to preserve for review.";
            }
        }

        GitClient.GitResult r = git.run(repoRoot, List.of(
                "worktree", "remove", path.toString(), "--force"));
        if (!r.isSuccess()) {
            return "Error: failed to remove worktree directory for '" + name + "': " + r.getOutput();
        }
        // best-effort 删除分支(分支不存在不影响)
        git.run(repoRoot, List.of("branch", "-D", "wt/" + name));

        logEvent("remove", name, null);
        log.info("[Worktree] removed {}", name);
        return "Worktree '" + name + "' removed";
    }

    /**
     * 统计 worktree 内未提交文件数 + 未推送 commit 数。
     *
     * @return [files, commits];出错返 [-1, -1]
     */
    private int[] countWorktreeChanges(Path path) {
        try {
            GitClient.GitResult statusR = git.run(path, List.of("status", "--porcelain"));
            if (!statusR.isSuccess()) return new int[]{-1, -1};
            int files = (int) statusR.getOutput().lines().filter(l -> !l.isBlank()).count();
            // "(no output)" 实际是 0 行
            if ("(no output)".equals(statusR.getOutput())) files = 0;

            GitClient.GitResult logR = git.run(path, List.of(
                    "log", "@{push}..HEAD", "--oneline"));
            // log 失败可能是因为没设 upstream (比如新分支),按 0 算
            int commits = !logR.isSuccess() ? 0
                    : (int) logR.getOutput().lines().filter(l -> !l.isBlank()).count();
            if ("(no output)".equals(logR.getOutput())) commits = 0;
            return new int[]{files, commits};
        } catch (Exception e) {
            log.warn("[Worktree] count changes failed for {}: {}", path, e.toString());
            return new int[]{-1, -1};
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Keep
    // ─────────────────────────────────────────────────────────────

    /**
     * 保留 worktree 给人工 review,只 log "keep" event。
     */
    public String keep(String name) {
        String err = validateName(name);
        if (err != null) return "Error: " + err;
        logEvent("keep", name, null);
        log.info("[Worktree] kept {}", name);
        return "Worktree '" + name + "' kept for review (branch: wt/" + name + ")";
    }

    // ─────────────────────────────────────────────────────────────
    //  审计日志
    // ─────────────────────────────────────────────────────────────

    /** Append 一行 JSON 事件到 {@code <worktreeDir>/events.jsonl}。 */
    private void logEvent(String type, String worktreeName, String taskId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("worktree", worktreeName);
        event.put("task_id", taskId == null ? "" : taskId);
        event.put("ts", System.currentTimeMillis());
        try {
            Files.createDirectories(worktreeDir);
            Path eventsFile = worktreeDir.resolve("events.jsonl");
            String line = json.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(eventsFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 审计失败不影响主路径,log warn 即可
            log.warn("[Worktree] failed to log event {}/{}: {}", type, worktreeName, e.toString());
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            log.warn("[Worktree] failed to log event {}/{}: {}", type, worktreeName, e.toString());
        }
    }
}
