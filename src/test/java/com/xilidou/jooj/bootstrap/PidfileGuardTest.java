package com.xilidou.jooj.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link PidfileGuard} 的启动约束行为。
 *
 * <p>关注点:
 * <ul>
 *   <li>新目录 → 写 pidfile 成功</li>
 *   <li>已存在但内容 invalid → warn 后覆盖</li>
 *   <li>已存在但对应 pid 已死 → 覆盖</li>
 *   <li>已存在且对应 pid 仍活 → 抛 IllegalStateException 拒启</li>
 *   <li>pidfile 内容匹配当前 JVM pid</li>
 * </ul>
 */
class PidfileGuardTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("首次启动 —— pidfile 不存在,acquire 写入当前 pid")
    void first_acquire_writes_current_pid() throws IOException {
        PidfileGuard.acquire(tmp);

        Path pidFile = tmp.resolve(PidfileGuard.PID_FILE_NAME);
        assertTrue(Files.exists(pidFile), "pidfile 应被创建");
        Long written = PidfileGuard.readPid(pidFile);
        assertEquals(ProcessHandle.current().pid(), written,
                "写入的 pid 应等于当前 JVM pid");
    }

    @Test
    @DisplayName("残留 pidfile —— pid 已死,覆盖之(不抛)")
    void stale_pidfile_dead_pid_is_taken_over() throws IOException {
        Path pidFile = tmp.resolve(PidfileGuard.PID_FILE_NAME);
        // 用一个几乎肯定不存在的 pid(超大值)
        long deadPid = 987654321L;
        assertFalse(PidfileGuard.isPidAlive(deadPid),
                "前置条件:987654321 应该不是活的进程");
        Files.writeString(pidFile, String.valueOf(deadPid));

        assertDoesNotThrow(() -> PidfileGuard.acquire(tmp));

        Long after = PidfileGuard.readPid(pidFile);
        assertEquals(ProcessHandle.current().pid(), after,
                "pidfile 应被覆盖为当前 pid");
    }

    @Test
    @DisplayName("残留 pidfile —— 内容 invalid,覆盖之(不抛)")
    void invalid_pidfile_is_overridden() throws IOException {
        Path pidFile = tmp.resolve(PidfileGuard.PID_FILE_NAME);
        Files.writeString(pidFile, "not-a-number");

        assertDoesNotThrow(() -> PidfileGuard.acquire(tmp));

        Long after = PidfileGuard.readPid(pidFile);
        assertEquals(ProcessHandle.current().pid(), after);
    }

    @Test
    @DisplayName("残留 pidfile —— 空内容,覆盖之")
    void empty_pidfile_is_overridden() throws IOException {
        Path pidFile = tmp.resolve(PidfileGuard.PID_FILE_NAME);
        Files.writeString(pidFile, "");

        assertDoesNotThrow(() -> PidfileGuard.acquire(tmp));

        Long after = PidfileGuard.readPid(pidFile);
        assertEquals(ProcessHandle.current().pid(), after);
    }

    @Test
    @DisplayName("活跃实例存在 —— acquire 抛 IllegalStateException")
    void alive_instance_blocks_acquire() throws IOException {
        Path pidFile = tmp.resolve(PidfileGuard.PID_FILE_NAME);
        // 用当前 JVM 之外的活 pid —— 用 parent process(shell / IDE)几乎总活着
        long parentPid = ProcessHandle.current().parent()
                .map(ProcessHandle::pid)
                .orElse(1L); // 兜底:pid=1 (init) 在 Unix 上永远活着
        assertTrue(PidfileGuard.isPidAlive(parentPid),
                "前置条件:parent pid " + parentPid + " 应该是活的");
        assertNotEquals(ProcessHandle.current().pid(), parentPid,
                "前置条件:parent pid 不能等于当前 pid(否则测试路径变'同 pid 复用'分支)");
        Files.writeString(pidFile, String.valueOf(parentPid));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PidfileGuard.acquire(tmp));
        assertTrue(ex.getMessage().contains(String.valueOf(parentPid)),
                "错误消息应含被占用的 pid,实际:" + ex.getMessage());
        assertTrue(ex.getMessage().contains("Multi-process"),
                "错误消息应明示不支持多进程,实际:" + ex.getMessage());
    }

    @Test
    @DisplayName("readPid: 各种解析场景")
    void readPid_edge_cases() throws IOException {
        Path pidFile = tmp.resolve("test.pid");

        Files.writeString(pidFile, "42");
        assertEquals(42L, PidfileGuard.readPid(pidFile));

        Files.writeString(pidFile, "  42  \n");
        assertEquals(42L, PidfileGuard.readPid(pidFile),
                "trim 空白 + 换行");

        Files.writeString(pidFile, "abc");
        assertNull(PidfileGuard.readPid(pidFile), "非数字 → null");

        Files.writeString(pidFile, "");
        assertNull(PidfileGuard.readPid(pidFile), "空 → null");
    }
}
