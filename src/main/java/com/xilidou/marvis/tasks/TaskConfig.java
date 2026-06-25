package com.xilidou.marvis.tasks;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Task System 配置。
 *
 * <p>跟 {@link com.xilidou.marvis.memory.MemoryConfig} 同模式:纯 POJO,
 * 无 Spring 依赖;测试用全参构造器覆盖默认值,生产用 {@link TasksConfiguration}
 * 从 {@link com.xilidou.marvis.MarvisProperties.Tasks} 转出。
 *
 * <p>当前只有 1 个字段({@code tasksDir}),保留这个类是因为:
 * <ul>
 *   <li>跟 MemoryConfig 形态一致,审美上整齐</li>
 *   <li>测试可以 {@code new TaskConfig(tempDir)} 注入隔离的 {@code @TempDir}</li>
 *   <li>未来加(threshold / lock 文件等)字段时不用改外部签名</li>
 * </ul>
 */
public class TaskConfig {

    private final Path tasksDir;

    /** 默认值构造器(生产用):cwd/.tasks/。 */
    public TaskConfig() {
        this(defaultTasksDir());
    }

    /** 全参构造器(测试 / 自定义)。 */
    public TaskConfig(Path tasksDir) {
        if (tasksDir == null) {
            throw new IllegalArgumentException("tasksDir must not be null");
        }
        this.tasksDir = tasksDir;
    }

    private static Path defaultTasksDir() {
        return Paths.get(System.getProperty("user.dir"), ".tasks");
    }

    public Path tasksDir() {
        return tasksDir;
    }
}
