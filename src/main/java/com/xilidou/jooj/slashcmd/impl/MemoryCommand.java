package com.xilidou.jooj.slashcmd.impl;

import com.xilidou.jooj.memory.MemoryFile;
import com.xilidou.jooj.memory.MemoryStore;
import com.xilidou.jooj.memory.PendingMemoryStore;
import com.xilidou.jooj.memory.PendingMemoryStore.PendingMemory;
import com.xilidou.jooj.slashcmd.SlashCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * /memory —— Hermes Tier 3 P3.2 staged-write 管理命令(s21 Demo 27)。
 *
 * <p>3 个 action:
 * <ul>
 *   <li>{@code /memory pending} —— 列出待审批的提案,带 id / proposedAt / source / 一行预览</li>
 *   <li>{@code /memory approve <id>} —— 把指定提案 promote 到正式 MemoryStore</li>
 *   <li>{@code /memory reject <id>} —— 直接丢弃提案,不写 store</li>
 * </ul>
 *
 * <p>**默认 action**:无参时等价 {@code /memory pending}(最常用,看一眼有什么待批)。
 *
 * <p>跟 {@link ClearCommand} 同模式 —— 纯客户端命令,不进 LLM、不进 history。
 */
@Component
@Slf4j
public class MemoryCommand implements SlashCommand {

    private final PendingMemoryStore pendingStore;
    private final MemoryStore memoryStore;

    public MemoryCommand(PendingMemoryStore pendingStore, MemoryStore memoryStore) {
        this.pendingStore = pendingStore;
        this.memoryStore = memoryStore;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "Manage staged memory proposals (pending / approve <id> / reject <id>).";
    }

    @Override
    public String execute(String args, String sessionId) {
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("pending")) {
            return doPending();
        }

        // 解析 "approve <id>" 或 "reject <id>"
        String[] parts = trimmed.split("\\s+", 2);
        String action = parts[0].toLowerCase();
        String idArg = parts.length > 1 ? parts[1].strip() : "";

        switch (action) {
            case "approve":
                return doApprove(idArg);
            case "reject":
                return doReject(idArg);
            case "clear":
                return doClear();
            default:
                return "Unknown /memory action: " + action +
                        ". Use: /memory [pending] | /memory approve <id> | /memory reject <id> | /memory clear";
        }
    }

    private String doPending() {
        List<PendingMemory> all = pendingStore.readAll();
        if (all.isEmpty()) {
            return "No pending memory proposals.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Pending memory proposals (").append(all.size()).append("):\n");
        for (PendingMemory p : all) {
            MemoryFile mem = p.getMemory();
            sb.append("  #").append(p.getId())
                    .append(" [").append(formatDate(p.getProposedAt())).append("|")
                    .append(p.getSource()).append("|")
                    .append(mem.getType().slug()).append("] ")
                    .append(mem.getName()).append(": ")
                    .append(truncate(mem.getDescription(), 80))
                    .append('\n');
        }
        sb.append("\nUse `/memory approve <id>` to keep, `/memory reject <id>` to drop.");
        return sb.toString();
    }

    private String doApprove(String idArg) {
        Long id = parseId(idArg);
        if (id == null) {
            return "Error: /memory approve needs a numeric id. Try /memory pending first.";
        }
        Optional<PendingMemory> approved = pendingStore.approve(id);
        if (approved.isEmpty()) {
            return "Error: pending proposal #" + id + " not found.";
        }
        PendingMemory entry = approved.get();
        try {
            memoryStore.write(entry.getMemory());
            return "✓ Approved #" + id + " → memory: " + entry.getMemory().getName();
        } catch (Exception e) {
            // store.write 失败(quota 超 / IO 错)—— 回滚把 entry 放回 pending pool
            // (Demo 27 review 修复:之前直接 return error 让 entry 永久丢,LLM 不会
            // re-create;现在保留原 id,用户可重试 /memory approve <id>)
            log.warn("[Memory:Cmd] approve id={} failed at store.write: {}", id, e.toString());
            try {
                pendingStore.restore(entry);
                return "✗ Approved #" + id + " failed: " + e.getMessage()
                        + ". Restored to pending pool — fix the issue and retry /memory approve "
                        + id + ".";
            } catch (Exception restoreErr) {
                // 双重失败:store.write 失败 + restore 也失败(磁盘满 / FS 只读等极端)
                log.error("[Memory:Cmd] approve id={} double failure (write + restore): write={} restore={}",
                        id, e.toString(), restoreErr.toString());
                return "✗ Approved #" + id + " removed from pending, store.write failed: "
                        + e.getMessage() + ", AND restore failed: " + restoreErr.getMessage()
                        + ". Memory NOT persisted — manual recovery needed (entry: name="
                        + entry.getMemory().getName() + ").";
            }
        }
    }

    private String doReject(String idArg) {
        Long id = parseId(idArg);
        if (id == null) {
            return "Error: /memory reject needs a numeric id. Try /memory pending first.";
        }
        boolean removed = pendingStore.reject(id);
        if (!removed) {
            return "Error: pending proposal #" + id + " not found.";
        }
        return "✓ Rejected #" + id + " (dropped, not persisted to memory).";
    }

    private String doClear() {
        int n = pendingStore.clear();
        return n == 0
                ? "Pending pool already empty."
                : "✓ Cleared " + n + " pending proposal(s) without persisting.";
    }

    private static Long parseId(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatDate(long epochMs) {
        if (epochMs <= 0) return "?";
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }
}
