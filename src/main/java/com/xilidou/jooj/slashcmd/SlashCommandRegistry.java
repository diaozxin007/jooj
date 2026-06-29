package com.xilidou.jooj.slashcmd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SlashCommandRegistry —— 收集所有 {@link SlashCommand} bean,提供 query 路由。
 *
 * <h3>路由约定</h3>
 *
 * <ul>
 *   <li>{@link #isCommand(String)} — query.strip() 是否以 / 开头(且不是孤零零一个 /)</li>
 *   <li>{@link #dispatch(String, String)} — 拆出命令名 + args,查表执行</li>
 *   <li>未注册命令(/foo) → "Unknown command: /foo. Available: /clear, /help, ..."</li>
 *   <li>裸 / 或 // —— 视作未识别命令,走兜底</li>
 * </ul>
 *
 * <h3>内置 /help</h3>
 *
 * <p>/help 不是单独 bean,而是 registry 自己实现 —— 它要遍历自己,放外面会循环依赖。
 *
 * <h3>大小写</h3>
 *
 * <p>命令名查表前 lowercased,/CLEAR 等价 /clear。args 不动(可能含路径等敏感大小写)。
 */
@Component
@Slf4j
public class SlashCommandRegistry {

    /** LinkedHashMap 保持注册顺序 → /help 输出按注册次序稳定。 */
    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    public SlashCommandRegistry(List<SlashCommand> beans) {
        for (SlashCommand c : beans) {
            String n = c.name().toLowerCase();
            if (commands.containsKey(n)) {
                log.warn("Duplicate slash command name: /{} — keeping first", n);
                continue;
            }
            commands.put(n, c);
        }
        log.info("SlashCommandRegistry initialized: {} commands ({})",
                commands.size() + 1 /* +help */, String.join(", ", commands.keySet()) + ", help");
    }

    /** query 是否要走 slash 路由?(以 / 开头且 / 后至少一个非空字符) */
    public boolean isCommand(String query) {
        if (query == null) return false;
        String s = query.strip();
        return s.length() >= 2 && s.charAt(0) == '/' && s.charAt(1) != '/';
    }

    /**
     * 执行 query 对应的命令,返回执行文本。
     *
     * <p>调用方应已通过 {@link #isCommand} 判定;否则这里直接当作未识别命令走兜底。
     */
    public String dispatch(String query, String sessionId) {
        String s = query.strip();
        // 去掉前导 /
        String body = s.startsWith("/") ? s.substring(1) : s;
        int sp = body.indexOf(' ');
        String name = (sp < 0 ? body : body.substring(0, sp)).toLowerCase();
        String args = sp < 0 ? "" : body.substring(sp + 1).strip();

        if ("help".equals(name)) return helpText();

        SlashCommand cmd = commands.get(name);
        if (cmd == null) return unknownText(name);

        try {
            return cmd.execute(args, sessionId);
        } catch (Exception e) {
            log.warn("Slash command /{} threw: {}", name, e.toString());
            return "✗ Command /" + name + " failed: " + e.getMessage();
        }
    }

    /** 给 /help / 错误提示用的命令名列表(带前导 /,逗号分隔)。 */
    private String availableList() {
        StringBuilder sb = new StringBuilder("/help");
        for (String n : commands.keySet()) sb.append(", /").append(n);
        return sb.toString();
    }

    private String helpText() {
        StringBuilder sb = new StringBuilder("Available commands:\n");
        sb.append("  /help        Show this message.\n");
        for (SlashCommand c : commands.values()) {
            sb.append("  /").append(padRight(c.name(), 10))
              .append("  ").append(c.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String unknownText(String name) {
        return "Unknown command: /" + name + ". Available: " + availableList();
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /** 测试 / 调试用:已注册命令名(不含 help)。 */
    public Collection<String> registeredNames() {
        return commands.keySet();
    }
}
