package com.xilidou.jooj.bootstrap;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * jooj 的 home 目录工具类(参考 hermes 的 {@code ensure_hermes_home}):
 *
 * <ul>
 *   <li>统一约定:用户级状态文件落在 {@code ~/.jooj/}(.env / sessions / cron / ...)</li>
 *   <li>支持 {@code JOOJ_HOME} 环境变量 override —— 便于测试隔离 + Docker / CI 指向 {@code /data/.jooj}</li>
 *   <li>{@link #ensureHome(Path)} 幂等地建目录 + 在 POSIX 系统上设置 mode 0700(私密)</li>
 * </ul>
 *
 * <h3>为什么单独成类</h3>
 *
 * <p>除了 {@link JoojEnvBootstrap} 在启动期用,后续 {@code MemoryService} / {@code TasksService}
 * 想把 sessions / cron 数据迁到 {@code ~/.jooj/} 时也能复用 —— 不要每个 caller 自己拼路径。
 *
 * <p>这个类**不依赖 Spring**:它在 {@code EnvironmentPostProcessor} 阶段被调用,
 * 那时候连 Logger / ApplicationContext 都还没起来,纯静态工具最安全。
 */
public final class JoojHome {

    /** {@code ~/.jooj} 目录名(Windows 上也用同名,符合 Spring 把 {@code user.home} 当统一锚点的习惯)。 */
    public static final String DIR_NAME = ".jooj";

    /** {@code ~/.jooj/.env} 文件名(被 {@code spring.config.import} 读取)。 */
    public static final String ENV_FILE_NAME = ".env";

    /** {@code JOOJ_HOME} 环境变量 —— 测试 / Docker / CI 用来 override 默认路径。 */
    public static final String HOME_ENV_VAR = "JOOJ_HOME";

    private JoojHome() {
        // 工具类,禁止实例化
    }

    /**
     * 解析 home 目录路径(只算路径,不创建)。
     *
     * <p>优先级:
     * <ol>
     *   <li>{@code JOOJ_HOME} 环境变量(非空)</li>
     *   <li>{@code System.getProperty("user.home")} + {@value #DIR_NAME}</li>
     * </ol>
     */
    public static Path getHomePath() {
        String override = System.getenv(HOME_ENV_VAR);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), DIR_NAME);
    }

    /**
     * 幂等地确保 home 目录存在,并在 POSIX 文件系统上设置权限为 0700。
     *
     * <p>{@link Files#createDirectories(Path, java.nio.file.attribute.FileAttribute[])}
     * 已存在时 no-op。Windows 上不支持 POSIX 权限,跳过 chmod 不抛异常。
     *
     * @throws IOException 创建目录失败时(比如父目录无写权限)
     */
    public static void ensureHome(Path home) throws IOException {
        Files.createDirectories(home);
        // POSIX 系统(macOS / Linux)上把 mode 锁成 0700(rwx------),避免别的用户读到 .env 里的 secret。
        // Windows 的 FileSystem 不含 "posix" view —— 跳过,沿用 ACL 默认。
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(home, perms);
        }
    }

    /**
     * 返回 {@code ~/.jooj/.env} 路径(只算路径,不保证文件存在)。
     */
    public static Path getEnvPath(Path home) {
        return home.resolve(ENV_FILE_NAME);
    }

    /**
     * 取 {@code ~/.jooj/<name>/} 子目录,**幂等创建**(已存在不报错)。
     *
     * <p>Skill subdirs(cron / skills / ...)的入口。父目录 {@code home} 必须先经
     * {@link #ensureHome(Path)} 处理过,这里只关心子目录本身。
     *
     * <p>子目录**不主动设 0700** —— sub-config 通常允许同 user 的别的进程读
     * (例如 cron 的 worker process),严格 0700 反而会卡。如需更严的权限
     * 由 caller 自己加。
     *
     * @param home 父目录(通常是 {@link #getHomePath()} 的返回值)
     * @param name 子目录名,例如 {@code "cron"} / {@code "skills"} / {@code "sessions"}
     * @return 子目录的绝对路径
     */
    public static Path ensureSubdir(Path home, String name) throws IOException {
        Path sub = home.resolve(name);
        Files.createDirectories(sub);
        return sub;
    }
}
