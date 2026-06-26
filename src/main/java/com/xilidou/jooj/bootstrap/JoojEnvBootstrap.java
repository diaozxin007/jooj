package com.xilidou.jooj.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 启动前确保 {@code ~/.jooj/} 目录和 {@code .env} 文件存在 —— 学 hermes 的 ensure_hermes_home。
 *
 * <p>触发时机:Spring Boot 通过 {@code spring.factories} SPI 在 {@link ConfigurableEnvironment}
 * 准备好但 ConfigData 处理(application.yml 的 {@code spring.config.import})执行前调用。
 * 这正是我们需要的窗口 —— application.yml 里的 {@code spring.config.import:
 * optional:file:${user.home}/.jooj/.env} 解析时,文件已经备好。
 *
 * <h3>关键约束:此时还没有 Logger / ApplicationContext</h3>
 *
 * <p>不能用 {@code @Slf4j}(logback 还没起);用 Spring 自带的 {@link DeferredLog}
 * 缓存日志,等 Logger ready 后通过 {@link ApplicationPreparedEvent} 监听器 flush 出去。
 *
 * <h3>责任边界</h3>
 *
 * <p>本类**只负责把文件准备好**,不读 {@code .env} 内容 —— 解析/加载 KEY=VALUE 是
 * Spring {@code spring.config.import} 内置 dotenv 解析器的职责。这样我们不用
 * 重新造轮子(hermes 是因为没有 Spring 才自己写 _sanitize_env_lines)。
 */
public class JoojEnvBootstrap implements EnvironmentPostProcessor {

    /**
     * 用 DeferredLog 缓存启动期日志,等 ApplicationContext 起来再 flush。
     * Spring Boot 4 推荐用法:logger 实例放在静态字段,通过监听器在 ContextRefresh 时回放。
     */
    private static final DeferredLog log = new DeferredLog();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 注册一次 listener,等 logback 起来后 flush 缓存的日志(参考 Spring Boot 自己的
        // ConfigFileApplicationListener / ProfileNamesProcessor 套路)。
        application.addListeners((ApplicationListener<ApplicationPreparedEvent>) event ->
                log.replayTo(JoojEnvBootstrap.class));

        try {
            Path home = JoojHome.getHomePath();
            JoojHome.ensureHome(home);

            Path envFile = JoojHome.getEnvPath(home);
            if (!Files.exists(envFile)) {
                writeTemplate(envFile);
                log.info("Created jooj env template at " + envFile + " — edit to add API keys");
            } else {
                log.debug("jooj env file already present: " + envFile);
            }
        } catch (IOException e) {
            // 不抛 —— 启动期 IO 问题不该把整个应用拖死,
            // 用户即使没有 ~/.jooj/.env 也可以走 OS 环境变量启动。
            log.warn("Failed to ensure jooj home / .env: " + e.getMessage());
        }
    }

    /**
     * 原子写入模板文件,POSIX 系统设置 mode 0600(只有 owner 能读写,跟 SSH key 同级别)。
     *
     * <p>{@link StandardOpenOption#CREATE_NEW} 在文件已存在时抛
     * {@link FileAlreadyExistsException} —— 这正是我们想要的(避免覆盖用户已填的内容)。
     * 多进程并发启动时(罕见但可能,例如 IDE Run + CLI 同时启)第二个进程会落到 catch
     * 分支,日志记一笔但不抛。
     */
    private void writeTemplate(Path envFile) {
        try {
            Files.writeString(envFile, JoojEnvTemplate.DEFAULT,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(envFile, perms);
            }
        } catch (FileAlreadyExistsException raceWith) {
            // 并发场景:别的进程刚写完 —— 让对方赢,我们什么也不做
            log.debug("Lost race writing template at " + envFile + " (another process won)");
        } catch (IOException e) {
            log.warn("Failed to write template at " + envFile + ": " + e.getMessage());
        }
    }
}
