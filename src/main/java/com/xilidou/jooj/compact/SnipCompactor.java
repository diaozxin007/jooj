package com.xilidou.jooj.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.llm.domain.LlmMessage;
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
 * <p>P2 Step G:messages 已是 canonical {@link LlmMessage},占位用
 * {@link LlmMessage#userText}。归档 JSONL 是 canonical JSON shape。
 */
@Slf4j
public class SnipCompactor {

    static final String SNIPPED_PREFIX = "[snipped ";

    private static final DateTimeFormatter ARCHIVE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final CompactConfig config;
    private final ObjectMapper json;

    public SnipCompactor(CompactConfig config) {
        this.config = config;
        this.json = JsonMappers.newMapper();
    }

    public boolean apply(List<LlmMessage> messages) {
        int total = messages.size();
        if (total <= config.maxMessages()) {
            return false;
        }

        int headEnd = config.snipHeadKeep();
        int tailStart = total - (config.maxMessages() - config.snipHeadKeep());

        headEnd = MessageBoundary.adjustHeadEnd(messages, headEnd);
        tailStart = MessageBoundary.adjustTailStart(messages, tailStart);

        if (headEnd >= tailStart) {
            return false;
        }

        int snipped = tailStart - headEnd;

        List<LlmMessage> middle = new ArrayList<>(messages.subList(headEnd, tailStart));
        Path archive = archiveMiddle(middle);
        String placeholder = archive != null
                ? SNIPPED_PREFIX + snipped + " messages, archived to " + archive.toAbsolutePath() + "]"
                : SNIPPED_PREFIX + snipped + " messages]";

        List<LlmMessage> rebuilt = new ArrayList<>(messages.subList(0, headEnd));
        rebuilt.add(LlmMessage.userText(placeholder));
        rebuilt.addAll(messages.subList(tailStart, total));

        messages.clear();
        messages.addAll(rebuilt);

        log.info("[Compact L1] snip removed {} middle messages, total {} → {}, archive={}",
                snipped, total, rebuilt.size(),
                archive != null ? archive.toAbsolutePath() : "<failed>");
        return true;
    }

    private Path archiveMiddle(List<LlmMessage> middle) {
        try {
            Path dir = config.transcriptDir();
            Files.createDirectories(dir);
            String filename = "snip-" + LocalDateTime.now().format(ARCHIVE_TS) + ".jsonl";
            Path file = dir.resolve(filename);
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (LlmMessage m : middle) {
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
