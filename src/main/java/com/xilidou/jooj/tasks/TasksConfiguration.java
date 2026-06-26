package com.xilidou.jooj.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Task System(s12)的 Spring 装配。
 *
 * <p>跟 {@link com.xilidou.jooj.memory.MemoryConfiguration} 1:1 模式:
 * <ul>
 *   <li>{@link TaskConfig} —— 从 {@link JoojProperties.Tasks} 转出</li>
 *   <li>{@link TaskStore} —— 接收 {@link TaskConfig} + {@code joojObjectMapper}</li>
 *   <li>{@link TaskService} —— 接收 {@link TaskStore}</li>
 * </ul>
 *
 * <p>{@link TaskService} / {@link TaskStore} / {@link TaskConfig} 都不自己 {@code @Component},
 * 跟 Memory / Compact 保持一致 —— model 层 framework-agnostic,测试 {@code new TaskService(...)}
 * 不依赖 Spring 容器。
 */
@Configuration
public class TasksConfiguration {

    @Bean
    public TaskConfig taskConfig(JoojProperties props) {
        Path tasksDir = Paths.get(props.getTasks().getTasksDir())
                .toAbsolutePath().normalize();
        return new TaskConfig(tasksDir);
    }

    @Bean
    public TaskStore taskStore(TaskConfig config,
                               @Qualifier("joojObjectMapper") ObjectMapper json) {
        return new TaskStore(config, json);
    }

    @Bean
    public TaskService taskService(TaskStore store) {
        return new TaskService(store);
    }
}
