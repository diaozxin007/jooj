package com.xilidou.marvis.compact;

import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * L3 tool_result_budget:大 tool_result 落盘 + 替换为 stub。
 *
 * <p>策略:
 * <ol>
 *   <li>遍历 messages 里所有 tool_result block</li>
 *   <li>content 字符数 > {@link CompactConfig#maxToolResultBytes()} → 写到磁盘
 *       {@code <taskOutputDir>/<tool_use_id>.txt}</li>
 *   <li>content 替换为 stub:{@code [Output too large (12.3KB). Full output saved to:
 *       /abs/path. Read the file to see full content.]}</li>
 * </ol>
 *
 * <p>为什么这种 stub 模型能懂:格式跟 RTK 的 {@code <persisted-output>} 一致,
 * Claude/GPT-4 在训练数据里见过大量"输出落盘+路径"的模式,会自然推断:
 * 需要原文时调 {@code read_file} 读那个文件。
 *
 * <p>幂等性:stub 以 {@link #STUB_PREFIX} 开头,L3 自己再次 apply 时检查前缀跳过。
 *
 * <p>与 L1/L2 的协调:
 * <ul>
 *   <li>{@link CompactPipeline} 跑 L3 → L1 → L2 顺序:L3 先把大块换成短 stub,
 *       L1 看到的 messages 已经"瘦身",更容易判断要不要裁,L2 最后把旧的中等
 *       大小的内容占位</li>
 *   <li>L3 stub 通常 < 120 字符(默认 minPlaceholderLen),所以 L2 自然不动它;
 *       但 {@link MicroCompactor} 也加了"L3 stub 前缀"检查,双重保险</li>
 * </ul>
 *
 * <p>磁盘错误处理:写盘失败(磁盘满 / 权限问题 / 路径不存在)只 warn 日志,
 * 不抛异常。原 content 保留不动——压缩失败比 agent 崩溃更可接受。
 *
 * <p>不做的事(留给后续 session):
 * <ul>
 *   <li>不做"已落盘 → 模型 read_file → 再压缩同一个文件" 的去重(L4 范畴)</li>
 *   <li>不做基于 token 估算的精确裁剪(教学版用字符数,生产用可上 tiktoken/anthropic-tokenizer)</li>
 *   <li>不做磁盘配额管理(.task_outputs 永远累积,需要外部清理)</li>
 * </ul>
 */
@Slf4j
public class BudgetCompactor {

    /**
     * Stub 前缀。所有 L3 写出的占位都以这个开头,用于幂等性检查
     * 和与 L2 PLACEHOLDER 区分。
     */
    static final String STUB_PREFIX = "[Output too large";

    private final CompactConfig config;

    public BudgetCompactor(CompactConfig config) {
        this.config = config;
    }

    /**
     * 在 {@code messages} 上原地修改 ToolResultBlock.content。返回 true 表示
     * 至少落盘了一个。
     *
     * @param messages 对话历史(可能 mutate 内部 ToolResultBlock.content)
     * @return 是否实际落盘过
     */
    public boolean apply(List<MessageParam> messages) {
        List<ToolResultBlock> all = collectToolResults(messages);
        if (all.isEmpty()) return false;

        int persisted = 0;
        for (ToolResultBlock b : all) {
            String s = String.valueOf(b.getContent());
            // 长度门槛 + 幂等性检查
            if (s.length() <= config.maxToolResultBytes()) continue;
            if (s.startsWith(STUB_PREFIX)) continue;

            String stub = persistAndStub(b.getToolUseId(), s);
            if (stub != null) {
                b.setContent(stub);
                persisted++;
            }
        }
        if (persisted > 0) {
            log.info("[Compact L3] budget persisted {} large tool results to {}",
                    persisted, config.taskOutputDir());
            return true;
        }
        return false;
    }

    /**
     * 落盘 + 生成 stub。失败返回 null(调用方保留原 content)。
     */
    private String persistAndStub(String toolUseId, String content) {
        try {
            Path dir = config.taskOutputDir();
            Files.createDirectories(dir);
            // 文件名用 tool_use_id;为空时降级为时间戳
            String filename = (toolUseId != null && !toolUseId.isBlank())
                    ? sanitize(toolUseId) + ".txt"
                    : "tool_result_" + System.identityHashCode(content) + ".txt";
            Path file = dir.resolve(filename);
            Files.writeString(file, content, StandardCharsets.UTF_8);

            double kb = content.length() / 1024.0;
            return String.format(Locale.ROOT,
                    "%s (%.1fKB). Full output saved to: %s. Read the file to see full content.]",
                    STUB_PREFIX, kb, file.toAbsolutePath());
        } catch (IOException e) {
            log.warn("[Compact L3] failed to persist tool result for id={}: {}",
                    toolUseId, e.toString());
            return null;
        }
    }

    /**
     * 文件名清洗:tool_use_id 一般是 toolu_01XYZ 这种安全字符,
     * 但为了防御反斜杠 / 路径穿越,这里去掉所有非 [A-Za-z0-9_-] 字符。
     */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static List<ToolResultBlock> collectToolResults(List<MessageParam> msgs) {
        List<ToolResultBlock> out = new ArrayList<>();
        for (MessageParam m : msgs) {
            if (!"user".equals(m.getRole())) continue;
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof ToolResultBlock trb) {
                    out.add(trb);
                }
            }
        }
        return out;
    }
}
