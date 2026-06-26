package com.xilidou.jooj.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojProperties;
import com.xilidou.jooj.config.JacksonConfig;
import com.xilidou.jooj.tasks.TaskConfig;
import com.xilidou.jooj.tasks.TaskRecord;
import com.xilidou.jooj.tasks.TaskService;
import com.xilidou.jooj.tasks.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 锁定 {@link WorktreeService} 的业务逻辑。
 *
 * <p>用 mock {@link GitClient} 验业务逻辑(参数顺序、错误返回、status 检查),
 * 不真起 git 进程。
 */
class WorktreeServiceTest {

    @TempDir
    Path tempDir;

    private WorktreeService service;
    private TaskService taskService;
    private GitClient git;

    @BeforeEach
    void setUp() {
        // 模拟 jooj 主目录在 tempDir 下,user.dir 已经被 JVM 设了,WorktreeService 用 user.dir
        // 但 worktreeDir 走配置 → tempDir 子目录
        ObjectMapper json = JacksonConfig.newMapper();
        TaskConfig taskConfig = new TaskConfig(tempDir.resolve(".tasks"));
        TaskStore taskStore = new TaskStore(taskConfig, json);
        taskService = new TaskService(taskStore);

        JoojProperties props = new JoojProperties();
        props.getTeam().setWorktreeDir(tempDir.resolve(".worktrees").toString());

        git = mock(GitClient.class);
        service = new WorktreeService(props, taskService, git, json);
    }

    // ─────────────────────────────────────────────────────────────
    //  validateName
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validateName 拒绝 null/blank/path traversal/非法字符")
    void validate_name_rejects_invalid() {
        assertNotNull(WorktreeService.validateName(null));
        assertNotNull(WorktreeService.validateName(""));
        assertNotNull(WorktreeService.validateName("."));
        assertNotNull(WorktreeService.validateName(".."));
        assertNotNull(WorktreeService.validateName("foo/bar"));      // 含 /
        assertNotNull(WorktreeService.validateName("foo bar"));      // 含空格
        assertNotNull(WorktreeService.validateName("a".repeat(65))); // 超 64 长度
    }

    @Test
    @DisplayName("validateName 接受合法 name")
    void validate_name_accepts_valid() {
        assertNull(WorktreeService.validateName("auth-refactor"));
        assertNull(WorktreeService.validateName("ui_v2"));
        assertNull(WorktreeService.validateName("v1.0"));
        assertNull(WorktreeService.validateName("alice"));
    }

    // ─────────────────────────────────────────────────────────────
    //  create
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create 成功:git 命令成功 + 返路径 + 写 events.jsonl")
    void create_happy_path() {
        when(git.run(any(), eq(List.of("worktree", "add",
                tempDir.resolve(".worktrees/auth").toString(),
                "-b", "wt/auth", "HEAD"))))
                .thenReturn(GitClient.GitResult.ok("Created worktree"));

        String result = service.create("auth", null);
        assertTrue(result.startsWith("Worktree 'auth' created at "));

        // events.jsonl 应该有一行 type=create
        Path events = tempDir.resolve(".worktrees/events.jsonl");
        assertTrue(Files.exists(events));
    }

    @Test
    @DisplayName("create 名字非法 → 失败 + git 不被调用")
    void create_invalid_name_no_git_call() {
        String r = service.create("../bad", null);
        assertTrue(r.startsWith("Error:"));
        verifyNoInteractions(git);
    }

    @Test
    @DisplayName("create 路径已存在 → 失败")
    void create_existing_path_fails() throws Exception {
        Files.createDirectories(tempDir.resolve(".worktrees/existing"));
        String r = service.create("existing", null);
        assertTrue(r.startsWith("Error:") && r.contains("already exists"));
        verifyNoInteractions(git);
    }

    @Test
    @DisplayName("create git 失败 → 返 git 错误")
    void create_git_failure() {
        when(git.run(any(), anyList()))
                .thenReturn(GitClient.GitResult.error("fatal: not a git repository"));
        String r = service.create("foo", null);
        assertTrue(r.startsWith("Error:") && r.contains("not a git repository"));
    }

    @Test
    @DisplayName("create 带 task_id 时绑 task.worktree 字段")
    void create_with_task_binds() {
        String taskId = taskService.create("Refactor auth", "", new ArrayList<>());
        when(git.run(any(), anyList())).thenReturn(GitClient.GitResult.ok("ok"));

        String r = service.create("auth", taskId);
        assertTrue(r.startsWith("Worktree 'auth' created"));

        TaskRecord refreshed = taskService.get(taskId).orElseThrow();
        assertEquals("auth", refreshed.getWorktree());
    }

    // ─────────────────────────────────────────────────────────────
    //  remove
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("remove 路径不存在 → 失败")
    void remove_missing_path() {
        String r = service.remove("nonexistent", false);
        assertTrue(r.startsWith("Error:") && r.contains("not found"));
        verifyNoInteractions(git);
    }

    @Test
    @DisplayName("remove 有未提交改动 + discard=false → 拒绝")
    void remove_refused_when_dirty() throws Exception {
        Files.createDirectories(tempDir.resolve(".worktrees/auth"));
        // status 返回 2 行(2 个未提交文件)
        when(git.run(eq(tempDir.resolve(".worktrees/auth")),
                eq(List.of("status", "--porcelain"))))
                .thenReturn(GitClient.GitResult.ok("M a.txt\nM b.txt"));
        when(git.run(eq(tempDir.resolve(".worktrees/auth")),
                eq(List.of("log", "@{push}..HEAD", "--oneline"))))
                .thenReturn(GitClient.GitResult.ok("(no output)"));

        String r = service.remove("auth", false);
        assertTrue(r.startsWith("Error:") && r.contains("uncommitted"));
        // git worktree remove 不应被调用
        verify(git, never()).run(any(), eq(List.of(
                "worktree", "remove",
                tempDir.resolve(".worktrees/auth").toString(), "--force")));
    }

    @Test
    @DisplayName("remove discard=true → 强删,即使有改动")
    void remove_discard_true_force() throws Exception {
        Files.createDirectories(tempDir.resolve(".worktrees/auth"));
        when(git.run(eq(java.nio.file.Paths.get(System.getProperty("user.dir"))
                        .toAbsolutePath().normalize()),
                eq(List.of("worktree", "remove",
                        tempDir.resolve(".worktrees/auth").toString(), "--force"))))
                .thenReturn(GitClient.GitResult.ok("Removed"));
        when(git.run(eq(java.nio.file.Paths.get(System.getProperty("user.dir"))
                        .toAbsolutePath().normalize()),
                eq(List.of("branch", "-D", "wt/auth"))))
                .thenReturn(GitClient.GitResult.ok("Deleted"));

        String r = service.remove("auth", true);
        assertTrue(r.endsWith("removed"));
        // status / log 不应被检查
        verify(git, never()).run(any(), eq(List.of("status", "--porcelain")));
    }

    // ─────────────────────────────────────────────────────────────
    //  keep
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("keep 不调 git,只 log event")
    void keep_only_logs() {
        String r = service.keep("auth");
        assertTrue(r.contains("kept for review"));
        verifyNoInteractions(git);

        Path events = tempDir.resolve(".worktrees/events.jsonl");
        assertTrue(Files.exists(events));
    }

    @Test
    @DisplayName("keep 名字非法 → 失败")
    void keep_invalid_name() {
        String r = service.keep("../bad");
        assertTrue(r.startsWith("Error:"));
    }

    // ─────────────────────────────────────────────────────────────
    //  bindTask
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bindTask 设置 task.worktree 字段")
    void bind_task_sets_field() {
        String taskId = taskService.create("Test task", "", new ArrayList<>());
        String err = service.bindTask(taskId, "auth");
        assertNull(err);

        assertEquals("auth", taskService.get(taskId).orElseThrow().getWorktree());
    }

    @Test
    @DisplayName("bindTask 不存在的 task → 错误")
    void bind_task_missing_task() {
        String err = service.bindTask("task_999", "auth");
        assertNotNull(err);
        assertTrue(err.contains("not found"));
    }

    @Test
    @DisplayName("bindTask 非法 worktree 名 → 错误")
    void bind_task_invalid_worktree_name() {
        String taskId = taskService.create("Test", "", new ArrayList<>());
        String err = service.bindTask(taskId, "../bad");
        assertNotNull(err);
    }

    // ─────────────────────────────────────────────────────────────
    //  pathFor
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pathFor 返回 worktreeDir / name(不检查是否存在)")
    void path_for_returns_resolved_path() {
        Path p = service.pathFor("auth");
        assertEquals(tempDir.resolve(".worktrees/auth").toAbsolutePath().normalize(), p);
    }
}
