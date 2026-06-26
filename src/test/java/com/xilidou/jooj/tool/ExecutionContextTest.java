package com.xilidou.jooj.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link ExecutionContext} 的工厂 + cwdOr 行为。
 *
 * <p>纯 record 数据类测试,确保 lead/forTeammate/inWorktree 三个工厂的字段值正确。
 */
class ExecutionContextTest {

    @Test
    @DisplayName("lead() 工厂:cwd null,agentName='lead',worktreeName null")
    void lead_factory() {
        ExecutionContext ctx = ExecutionContext.lead();
        assertNull(ctx.cwd());
        assertEquals("lead", ctx.agentName());
        assertNull(ctx.worktreeName());
    }

    @Test
    @DisplayName("forTeammate(name) 工厂:cwd null,agentName=name,worktreeName null")
    void for_teammate_factory() {
        ExecutionContext ctx = ExecutionContext.forTeammate("alice");
        assertNull(ctx.cwd());
        assertEquals("alice", ctx.agentName());
        assertNull(ctx.worktreeName());
    }

    @Test
    @DisplayName("inWorktree(name, wtName, wtPath) 工厂:三字段都填好")
    void in_worktree_factory() {
        Path wtPath = Paths.get("/tmp/wt/auth-refactor");
        ExecutionContext ctx = ExecutionContext.inWorktree("alice", "auth-refactor", wtPath);
        assertEquals(wtPath, ctx.cwd());
        assertEquals("alice", ctx.agentName());
        assertEquals("auth-refactor", ctx.worktreeName());
    }

    @Test
    @DisplayName("cwdOr(default):有 cwd 优先,无 cwd fallback default")
    void cwd_or_fallback() {
        Path defaultPath = Paths.get("/default/dir");
        Path explicit = Paths.get("/explicit/dir");

        ExecutionContext leadCtx = ExecutionContext.lead();
        assertEquals(defaultPath, leadCtx.cwdOr(defaultPath));

        ExecutionContext wtCtx = ExecutionContext.inWorktree("alice", "wt", explicit);
        assertEquals(explicit, wtCtx.cwdOr(defaultPath));
    }

    @Test
    @DisplayName("cwdOrUserDir:无 cwd 时返 user.dir")
    void cwd_or_user_dir() {
        ExecutionContext ctx = ExecutionContext.lead();
        Path expected = Paths.get(System.getProperty("user.dir"));
        assertEquals(expected, ctx.cwdOrUserDir());

        // 有 cwd 时返 cwd,跟 user.dir 无关
        Path explicit = Paths.get("/some/wt");
        ExecutionContext wtCtx = ExecutionContext.inWorktree("alice", "wt", explicit);
        assertEquals(explicit, wtCtx.cwdOrUserDir());
    }
}
