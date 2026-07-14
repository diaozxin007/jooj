package com.xilidou.jooj.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JacksonConfig;
import com.xilidou.jooj.http.dto.MessageParam;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * L1 snip_compact:消息条数超阈值时,裁中间留头尾。
 *
 * <p>策略:
 * <ol>
 *   <li>消息总数 ≤ {@link CompactConfig#maxMessages()} → 不动</li>
 *   <li>头部保留前 {@link CompactConfig#snipHeadKeep()} 条</li>
 *   <li>尾部保留后 {@code maxMessages - snipHeadKeep} 条</li>
 *   <li>{@link MessageBoundary} 调整切口,保护 tool_use ↔ tool_result 配对</li>
 *   <li><b>被裁的中段消息落盘归档到 {@link CompactConfig#transcriptDir()} 的
 *       {@code snip-<ts>.jsonl}</b>,占位携带绝对路径:
 *       {@code [snipped N messages, archived to /abs/path.jsonl]}</li>
 *   <li>中间替换为一条 user 占位消息</li>
 * </ol>
 *
 * <p>为什么要归档:早期版本占位只有 {@code [snipped N messages]},重启后前端展示时用户
 * 只能看到干瘪的省略号,中间对话永久丢失。归档到磁盘后,前端可通过 {@code /api/snip-archive}
 * 拉回完整原文查看,归档失败降级为不带 path 的旧格式(向前兼容,不阻断压缩)。
 *
 * <p>替换占位为 {@code role=user},不是 {@code role=system},因为
 * Anthropic Messages API 只接受 user/assistant 两种 role(system 单独传)。
 *
 * <p>边界情况:调整后 headEnd ≥ tailStart 时不裁(头尾交叠或贴合)。
 *
 * <p>不会原地 mutate 任何 MessageParam,只 {@code messages.clear()} +
 * {@code messages.addAll(rebuilt)} 重建列表。被保留的 MessageParam 引用
 * 是同一个,但作为列表条目本身被替换。
 */
@Slf4j
public class SnipCompactor {

    /**
     * 占位文案前缀。前端用正则识别并渲染成可点击链接。
     * 完整格式:{@code [snipped N messages, archived to /abs/path.jsonl]}
     * 或旧格式:{@code [snipped N messages]}(归档失败时降级)。
     */
    static final String SNIPPED_PREFIX = "[snipped ";

    /** 归档文件名时间戳格式。与 HistoryCompactor.TRANSCRIPT_TS 保持一致的可读性。 */
    private static final DateTimeFormatter ARCHIVE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final CompactConfig config;
    private final ObjectMapper json;

    public SnipCompactor(CompactConfig config) {
        this.config = config;
        this.json = JacksonConfig.newMapper();
    }

    /**
     * 在 {@code messages} 上原地裁剪。返回 true 表示发生了裁剪。
     *
     * @param messages 对话历史(会被原地修改:替换为新 list 内容)
     * @return 是否实际修改了 messages
     */
    public boolean apply(List<MessageParam> messages) {
        int total = messages.size();
        if (total <= config.maxMessages()) {
            return false;
        }

        int headEnd = config.snipHeadKeep();
        int tailStart = total - (config.maxMessages() - config.snipHeadKeep());

        // 边界保护:tool_use ↔ tool_result 不能拆
        headEnd = MessageBoundary.adjustHeadEnd(messages, headEnd);
        tailStart = MessageBoundary.adjustTailStart(messages, tailStart);

        if (headEnd >= tailStart) {
            // 调整后头尾交叠或贴合,无中间可裁
            return false;
        }

        int snipped = tailStart - headEnd;

        // 归档中段消息(失败降级为不带 path 的占位)
        List<MessageParam> middle = new ArrayList<>(messages.subList(headEnd, tailStart));
        Path archive = archiveMiddle(middle);
        String placeholder = archive != null
                ? SNIPPED_PREFIX + snipped + " messages, archived to " + archive.toAbsolutePath() + "]"
                : SNIPPED_PREFIX + snipped + " messages]";

        // 用新 list 重建,不原地 mutate 单条 MessageParam(保护 snapshot 引用)
        List<MessageParam> rebuilt = new ArrayList<>(messages.subList(0, headEnd));
        rebuilt.add(MessageParam.user(placeholder));
        rebuilt.addAll(messages.subList(tailStart, total));

        messages.clear();
        messages.addAll(rebuilt);

        log.info("[Compact L1] snip removed {} middle messages, total {} → {}, archive={}",
                snipped, total, rebuilt.size(),
                archive != null ? archive.toAbsolutePath() : "<failed>");
        return true;
    }

    /**
     * 归档中段到 {@code transcriptDir/snip-<ts>.jsonl},失败返回 null。
     *
     * <p>每行一个 JSON,便于流式回读、grep、cat。与 {@link HistoryCompactor#archiveMiddle}
     * 同格式,前端 {@code /api/snip-archive} 读取时两种归档可复用同一解析路径。
     */
    private Path archiveMiddle(List<MessageParam> middle) {
        try {
            Path dir = config.transcriptDir();
            Files.createDirectories(dir);
            String filename = "snip-" + LocalDateTime.now().format(ARCHIVE_TS) + ".jsonl";
            Path file = dir.resolve(filename);
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (MessageParam m : middle) {
                    writer.write(json.writeValueAsString(m));
                    writer.newLine();
                }
            }
            return file;
        } catch (IOException e) {
            log.warn("[Compact L1] failed to archive middle: {}", e.toString());
            return null;
        }
    }
}