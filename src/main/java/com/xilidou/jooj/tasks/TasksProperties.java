package com.xilidou.jooj.tasks;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Task System(s12)的 yml → Java 桥接。
 *
 * <p>三分法(参见 [[Jooj项目_配置架构重构_规划]] D-05):
 * <ul>
 *   <li>{@link TasksProperties}(本类)—— {@code @ConfigurationProperties("jooj.tasks")}</li>
 *   <li>{@link TaskConfig} —— 运行时 POJO,{@code tasksDir} 已解析为绝对 {@link java.nio.file.Path}</li>
 *   <li>{@link TasksConfiguration} —— {@code @Bean} 装配</li>
 * </ul>
 *
 * <p>对应上游 s12 的 {@code TASKS_DIR = WORKDIR / ".tasks"}:
 * 每个 task 一个 JSON 文件,文件名 = task id(形如 {@code task_1729000000_3812.json})。
 * 教学版不加 file lock —— 与上游一致,jooj 单进程 REPL 不会并发写。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Tasks} 拆出,前缀 {@code jooj.tasks} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.tasks")
public class TasksProperties {

    /** task 文件目录(相对 cwd 或绝对路径)。默认 {@code .tasks}。 */
    private String tasksDir = ".tasks";
}
