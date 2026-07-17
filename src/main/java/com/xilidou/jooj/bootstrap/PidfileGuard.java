package com.xilidou.jooj.bootstrap;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * s22 D12:单 JVM 部署约束 —— 阻断第二个 jooj 实例启动。
 *
 * <h3>为什么需要</h3>
 *
 * <p>s22 事件驱动 transcript 假设**单进程写文件**:
 * <ul>
 *   <li>{@code TranscriptStore.append} 用 {@code FileChannel} append 模式,
 *       Linux/APFS 上 &lt;PIPE_BUF (4KB) 的 append 才原子</li>
 *   <li>{@code SessionService.saveHistory} 用整盘覆盖式,多进程会覆盖丢数据</li>
 *   <li>{@code SearchStore} 用单 SQLite Connection + WAL,jooj 假设单进程写</li>
 * </ul>
 *
 * <p>多进程共享 {@code ~/.jooj/} 会引发 transcript 撕裂、session JSON race、SQLite lock
 * 异常等一系列难以排查的问题。P5 通过 pidfile guard 从**启动期**就拒绝,把问题挡在门口。
 *
 * <h3>算法</h3>
 *
 * <p>{@link #acquire(Path)} 在 {@code JoojApplication.main} 启动最开始处调:
 *
 * <ol>
 *   <li>{@code ~/.jooj/.pid} 不存在 → 写当前 pid,返 true</li>
 *   <li>存在 + 内容非 valid long → warn log + 覆盖为当前 pid(残留文件)</li>
 *   <li>存在 + valid pid + 该 pid **已死** → 覆盖为当前 pid(上次崩溃残留)</li>
 *   <li>存在 + valid pid + 该 pid **仍活** → 抛 {@link IllegalStateException} 拒启</li>
 * </ol>
 *
 * <p>启动成功后注册 shutdown hook 删 pidfile,让下次启动干净。
 *
 * <p><b>不做</b>:
 * <ul>
 *   <li>不做跨机文件锁(NFS/S3 语义不可靠)—— 假设单机</li>
 *   <li>不做 stale pidfile 时间窗判断 —— 用 {@code ProcessHandle.isAlive} 更准</li>
 * </ul>
 */
@Slf4j
public final class PidfileGuard {

    /** pidfile 文件名(位于 JoojHome 下)。 */
    public static final String PID_FILE_NAME = ".pid";

    private PidfileGuard() {}

    /**
     * 尝试独占持有 pidfile。成功返回 true;有另一个活跃实例时抛异常拒启。
     *
     * <p>启动方式:
     * <pre>
     *   Path home = JoojHome.getHomePath();
     *   JoojHome.ensureHome(home);
     *   PidfileGuard.acquire(home);   // 若失败会抛异常,进程直接退出
     *   SpringApplication.run(...);
     * </pre>
     *
     * @param home JoojHome 目录(通常是 {@code ~/.jooj/})
     * @throws IllegalStateException 有另一个活跃实例
     * @throws IOException 写 pidfile 失败(磁盘/权限问题)
     */
    public static void acquire(Path home) throws IOException {
        Path pidFile = home.resolve(PID_FILE_NAME);
        long myPid = ProcessHandle.current().pid();

        if (Files.exists(pidFile)) {
            Long existing = readPid(pidFile);
            if (existing == null) {
                log.warn("[Bootstrap] pidfile {} contained invalid content, overriding", pidFile);
            } else if (existing == myPid) {
                // 极少数场景:同 pid 复用(不太可能,pid 命名空间可能重),视为 stale 继续
                log.warn("[Bootstrap] pidfile pid={} equals current pid, taking over", existing);
            } else if (isPidAlive(existing)) {
                throw new IllegalStateException(
                        "Another jooj instance is running (pid=" + existing + "). " +
                        "Multi-process deployment is not supported by s22 architecture; " +
                        "stop the other instance first or delete " + pidFile +
                        " if you are sure it is stale.");
            } else {
                log.warn("[Bootstrap] stale pidfile from dead pid={}, taking over", existing);
            }
        }

        writePid(pidFile, myPid);
        registerReleaseOnShutdown(pidFile, myPid);
        // s23 P8.3:降到 debug —— PidfileGuard 在 SpringApplication.run 之前调用,
        // 此时 logback-spring.xml 尚未加载,Spring Boot 默认 CONSOLE appender 会把 info
        // 泄漏到 stdout/stderr 污染前端 UI 显示。成功场景静默;失败/覆盖分支上面 log.warn 已覆盖。
        log.debug("[Bootstrap] pidfile acquired: {} (pid={})", pidFile, myPid);
    }

    // ── 内部工具 ──────────────────────────────────────────────

    /** 读 pidfile 内容 → Long,任何解析失败返 null(caller warn 覆盖)。 */
    static Long readPid(Path pidFile) {
        try {
            String content = Files.readString(pidFile).trim();
            if (content.isEmpty()) return null;
            return Long.parseLong(content);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** 检查 pid 是否是**活跃**进程。JDK 9+ {@link ProcessHandle} API。 */
    static boolean isPidAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** 覆盖式写 pidfile。 */
    static void writePid(Path pidFile, long pid) throws IOException {
        Files.writeString(pidFile, String.valueOf(pid),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /**
     * 注册 JVM shutdown hook,进程正常退出时删 pidfile,让下次启动干净。
     * kill -9 场景无效,靠 stale pid 检测兜底。
     */
    private static void registerReleaseOnShutdown(Path pidFile, long myPid) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Long current = readPid(pidFile);
                // 只删属于自己的 pidfile,防止误删覆盖到别的实例(极少数并发场景)
                if (current != null && current == myPid) {
                    Files.deleteIfExists(pidFile);
                }
            } catch (Exception e) {
                // shutdown hook 不该抛;log 一句就够
                log.warn("[Bootstrap] failed to release pidfile: {}", e.toString());
            }
        }, "jooj-pidfile-release"));
    }
}
