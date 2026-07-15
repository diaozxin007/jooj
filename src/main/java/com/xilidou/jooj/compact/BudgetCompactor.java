package com.xilidou.jooj.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * L3 tool_result_budget:大 tool_result 落盘 + 替换为 stub。
 *
 * <p>P2 Step G:canonical 类型 —— tool_result 在 {@link LlmRole#TOOL} 消息,
 * tool_call 在 {@link LlmRole#ASSISTANT} 消息,遍历简单。
 */
@Slf4j
public class BudgetCompactor {

    static final String STUB_PREFIX = "[Output too large";

    private final CompactConfig config;

    public BudgetCompactor(CompactConfig config) {
        this.config = config;
    }

    /**
     * 在 {@code messages} 上原地修改 LlmToolResult.output。返回 true 表示至少落盘了一个。
     *
     * <p><b>Ping-pong 防御</b>:tool_result 是 {@code read_file} 读 taskOutputDir 下
     * stub 文件返回时——即便超过阈值也**跳过**。
     */
    public boolean apply(List<LlmMessage> messages) {
        Map<String, LlmToolCall> uses = collectToolCalls(messages);
        List<LlmToolResult> all = collectToolResults(messages);
        if (all.isEmpty()) return false;

        int persisted = 0;
        int skipped = 0;
        for (LlmToolResult b : all) {
            String s = b.getOutput() != null ? b.getOutput() : "";
            if (s.length() <= config.maxToolResultBytes()) continue;
            if (s.startsWith(STUB_PREFIX)) continue;
            if (isSelfReadback(uses.get(b.getToolCallId()))) {
                skipped++;
                continue;
            }

            String stub = persistAndStub(b.getToolCallId(), s);
            if (stub != null) {
                b.setOutput(stub);
                persisted++;
            }
        }
        if (skipped > 0) {
            log.warn("[Compact L3] skipped {} self-readback tool_results (read_file/cat/head/tail on {})",
                    skipped, config.taskOutputDir());
        }
        if (persisted > 0) {
            log.info("[Compact L3] budget persisted {} large tool results to {}",
                    persisted, config.taskOutputDir());
            return true;
        }
        return false;
    }

    private boolean isSelfReadback(LlmToolCall use) {
        if (use == null || use.getName() == null) return false;
        JsonNode input = use.getInput();
        if (input == null || !input.isObject()) return false;

        String taskOutputAbs = config.taskOutputDir().toAbsolutePath().normalize().toString();
        String name = use.getName();
        if ("read_file".equals(name)) {
            JsonNode p = input.get("path");
            if (p == null || !p.isTextual()) return false;
            return pathInsideTaskOutputDir(p.asText(), taskOutputAbs);
        }
        if ("bash".equals(name)) {
            JsonNode cmd = input.get("command");
            if (cmd == null || !cmd.isTextual()) return false;
            String c = cmd.asText();
            return c.contains(taskOutputAbs) || c.contains(".task_outputs/tool-results/");
        }
        return false;
    }

    private boolean pathInsideTaskOutputDir(String userPath, String taskOutputAbs) {
        if (userPath == null) return false;
        try {
            Path p = Path.of(userPath).toAbsolutePath().normalize();
            if (p.toString().startsWith(taskOutputAbs)) return true;
        } catch (Exception ignored) {}
        return userPath.contains(taskOutputAbs) || userPath.contains(".task_outputs/tool-results/");
    }

    private String persistAndStub(String toolCallId, String content) {
        try {
            Path dir = config.taskOutputDir();
            Files.createDirectories(dir);
            String filename = (toolCallId != null && !toolCallId.isBlank())
                    ? sanitize(toolCallId) + ".txt"
                    : "tool_result_" + System.identityHashCode(content) + ".txt";
            Path file = dir.resolve(filename);
            Files.writeString(file, content, StandardCharsets.UTF_8);

            double kb = content.length() / 1024.0;
            return String.format(Locale.ROOT,
                    "%s (%.1fKB). Full output saved to: %s. Read the file to see full content.]",
                    STUB_PREFIX, kb, file.toAbsolutePath());
        } catch (IOException e) {
            log.warn("[Compact L3] failed to persist tool result for id={}: {}",
                    toolCallId, e.toString());
            return null;
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static List<LlmToolResult> collectToolResults(List<LlmMessage> msgs) {
        List<LlmToolResult> out = new ArrayList<>();
        for (LlmMessage m : msgs) {
            if (m == null || m.getRole() != LlmRole.TOOL || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolResult tr) out.add(tr);
            }
        }
        return out;
    }

    private static Map<String, LlmToolCall> collectToolCalls(List<LlmMessage> msgs) {
        Map<String, LlmToolCall> out = new HashMap<>();
        for (LlmMessage m : msgs) {
            if (m == null || m.getRole() != LlmRole.ASSISTANT || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmToolCall tc && tc.getId() != null) {
                    out.put(tc.getId(), tc);
                }
            }
        }
        return out;
    }
}
