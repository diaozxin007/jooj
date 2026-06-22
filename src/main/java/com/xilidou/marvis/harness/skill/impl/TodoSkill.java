package com.xilidou.marvis.harness.skill.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.harness.JacksonConfig;
import com.xilidou.marvis.harness.base.ToolCall;
import com.xilidou.marvis.harness.entity.ToolDefinition;
import com.xilidou.marvis.harness.entity.ToolResult;
import com.xilidou.marvis.harness.http.dto.InputSchema;
import com.xilidou.marvis.harness.skill.Skill;
import com.xilidou.marvis.harness.todo.TodoItem;
import com.xilidou.marvis.harness.todo.TodoStatus;
import com.xilidou.marvis.harness.todo.TodoStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * TodoSkill - 让 LLM 能"写"待办列表的工具（s05 核心）。
 *
 * <p>对应 Python s05 第 144 行的 {@code run_todo_write(todos)}。
 *
 * <h3>这个工具的本质：思考脚手架，不是"做事"</h3>
 *
 * <p>{@code todo_write} 不真做事——它**只是把 LLM 的计划记下来**。LLM 没有原生计划能力，
 * 必须显式把 task list 写出来才能在多轮对话里保持一致。这个工具给它一块"小白板"。
 *
 * <p>使用模式：
 * <pre>
 *   user: "重构 X 模块"
 *   LLM: 我先列计划 → todo_write([
 *           {content: "读 X 模块当前代码", status: "in_progress"},
 *           {content: "找出问题点", status: "pending"},
 *           {content: "重写", status: "pending"},
 *           {content: "跑测试", status: "pending"}
 *        ])
 *   LLM: 开始执行 → read_file(...)
 *   LLM: 完成第一步 → todo_write([
 *           {content: "读 X 模块当前代码", status: "completed"},   ← 改 completed
 *           {content: "找出问题点", status: "in_progress"},        ← 进入下一步
 *           ...
 *        ])
 * </pre>
 *
 * <p>每次调用是**整体替换**（LLM 给完整新列表），而不是 patch 单条——这与 Python 一致，
 * 也是更鲁棒的设计（不需要任务 id，避免 LLM 记错 id）。
 */
@Component
@Slf4j
public class TodoSkill implements Skill {

    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String RESET = "\033[0m";

    private final TodoStore store;
    private final ObjectMapper json;

    /**
     * Spring 注入构造器：自动用容器里的 TodoStore Bean。
     *
     * <p>{@code @Autowired} 显式标注是必要的——本类有 2 个构造器，
     * 不加注解 Spring 默认找无参的（不存在）会报 NoSuchMethodException。
     * 这是 Week 4 SkillRegistry 已经踩过的坑。
     */
    @Autowired
    public TodoSkill(TodoStore store) {
        this(store, JacksonConfig.newMapper());
    }

    /**
     * 测试构造器：可注入自己的 ObjectMapper。
     */
    public TodoSkill(TodoStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @Override
    public String getName() {
        return "todo";
    }

    @Override
    public String getDescription() {
        return "Manage a task list for the current coding session.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        // 注意：input_schema 必须告诉 LLM 每个 todo 是 {content, status} 形状
        // 这是 InputSchema 的 properties 嵌套用法
        Map<String, Object> todoItemSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "content", Map.of("type", "string", "description", "Task description"),
                        "status", Map.of(
                                "type", "string",
                                "enum", List.of("pending", "in_progress", "completed"),
                                "description", "Task status")
                ),
                "required", List.of("content", "status")
        );

        return List.of(new ToolDefinition(
                "todo_write",
                "Create and manage a task list for your current coding session. " +
                        "Call this BEFORE starting any multi-step task to plan your steps. " +
                        "Update statuses as you go.",
                InputSchema.object(
                        Map.of("todos", Map.of(
                                "type", "array",
                                "items", todoItemSchema,
                                "description", "The full updated todo list (overwrites previous)")),
                        "todos"
                )
        ));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!"todo_write".equals(call.getToolName())) {
            return new ToolResult(false, "Unknown tool: " + call.getToolName());
        }

        Object todosArg = call.getArguments().get("todos");
        if (todosArg == null) {
            return new ToolResult(false, "Error: 'todos' argument is required");
        }

        // LLM 给的是 List<Map>，转 List<TodoItem>
        List<TodoItem> todos;
        try {
            todos = json.convertValue(todosArg, new TypeReference<>() {});
        } catch (Exception e) {
            return new ToolResult(false,
                    "Error: invalid todos format. Expected array of {content, status}. " +
                            "Cause: " + e.getMessage());
        }

        // 校验：每条都必须有 content 和合法 status
        for (int i = 0; i < todos.size(); i++) {
            TodoItem t = todos.get(i);
            if (t.getContent() == null || t.getContent().isBlank()) {
                return new ToolResult(false,
                        "Error: todos[" + i + "] missing or empty 'content'");
            }
            if (t.getStatus() == null) {
                return new ToolResult(false,
                        "Error: todos[" + i + "] missing 'status'");
            }
        }

        store.replace(todos);
        printTodos(todos);
        log.info("[Todo] updated {} tasks ({} pending, {} in_progress, {} completed)",
                todos.size(),
                store.countByStatus(TodoStatus.PENDING),
                store.countByStatus(TodoStatus.IN_PROGRESS),
                store.countByStatus(TodoStatus.COMPLETED));

        return new ToolResult(true, "Updated " + todos.size() + " tasks");
    }

    /**
     * 在屏幕上打印 todo list（对应 Python 的彩色 ## Current Tasks 输出）。
     */
    private void printTodos(List<TodoItem> todos) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(YELLOW).append("## Current Tasks").append(RESET).append("\n");
        for (TodoItem t : todos) {
            String icon = switch (t.getStatus()) {
                case PENDING -> " ";
                case IN_PROGRESS -> CYAN + "▸" + RESET;
                case COMPLETED -> GREEN + "✓" + RESET;
            };
            sb.append("  [").append(icon).append("] ").append(t.getContent()).append("\n");
        }
        System.out.print(sb);
    }
}
