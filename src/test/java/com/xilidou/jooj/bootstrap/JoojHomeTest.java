package com.xilidou.jooj.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link JoojHome} 单元测试 —— 用 {@link TempDir} 完全避开真 {@code ~/.jooj/}。
 *
 * <p>注意:{@code JOOJ_HOME} 环境变量不能在 Java 进程内动态设置(System.getenv 不可写),
 * 所以这里测的是直接传 Path 的 API({@link JoojHome#ensureHome} / {@link JoojHome#getEnvPath})。
 * 环境变量分支留给集成测试或者后期 ProcessBuilder fork 子进程验证。
 */
class JoojHomeTest {

    @Test
    void getHomePath_defaultsToUserHomeDotJooj() {
        // 默认情况(没设 JOOJ_HOME):~/.jooj
        // 测试时 surefire 会注入 JOOJ_HOME=target/.jooj-test 做隔离,
        // 所以这里只能在 env 为空时才严格断言 user.home 默认路径。
        Path got = JoojHome.getHomePath();
        String envOverride = System.getenv(JoojHome.HOME_ENV_VAR);
        if (envOverride != null && !envOverride.isBlank()) {
            // 走的是 env override 分支 —— 验证 path 跟 env 一致
            assertThat(got).isEqualTo(Paths.get(envOverride));
        } else {
            // 没 env 时走默认分支
            Path expected = Paths.get(System.getProperty("user.home"), JoojHome.DIR_NAME);
            assertThat(got).isEqualTo(expected);
        }
    }

    @Test
    void ensureHome_createsDirectoryWhenMissing(@TempDir Path tempDir) throws IOException {
        Path home = tempDir.resolve(".jooj");
        assertThat(home).doesNotExist();

        JoojHome.ensureHome(home);

        assertThat(home).exists().isDirectory();
    }

    @Test
    void ensureHome_isIdempotent(@TempDir Path tempDir) throws IOException {
        Path home = tempDir.resolve(".jooj");
        JoojHome.ensureHome(home);

        // 二次调用不应抛(Files.createDirectories 已存在 no-op,setPosixFilePermissions 也允许重复)
        assertThatCode(() -> JoojHome.ensureHome(home)).doesNotThrowAnyException();
        assertThat(home).exists().isDirectory();
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void ensureHome_setsMode0700OnPosix(@TempDir Path tempDir) throws IOException {
        // POSIX-only 校验:目录权限必须是 rwx------(0700)
        Path home = tempDir.resolve(".jooj");
        JoojHome.ensureHome(home);

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(home);
            assertThat(perms).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
        }
    }

    @Test
    void getEnvPath_returnsHomeSlashDotEnv(@TempDir Path tempDir) {
        Path home = tempDir.resolve(".jooj");
        Path envPath = JoojHome.getEnvPath(home);
        assertThat(envPath).isEqualTo(home.resolve(".env"));
    }
}
