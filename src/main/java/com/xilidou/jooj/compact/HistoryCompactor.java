package com.xilidou.jooj.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JsonMappers;
import com.xilidou.jooj.llm.LlmClient;
import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmOpaque;
import com.xilidou.jooj.llm.domain.LlmRequest;
import com.xilidou.jooj.llm.domain.LlmResponse;
import com.xilidou.jooj.llm.domain.LlmRole;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmThinking;
import com.xilidou.jooj.llm.domain.LlmToolCall;
import com.xilidou.jooj.llm.domain.LlmToolResult;
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
 * L4 compact_history:LLM 摘要压缩(reactive,需要 API token)。
 *
 * <p>P2 Step G:messages 是 canonical {@link LlmMessage},renderMiddle 按
 * {@link LlmRole} dispatch + 遍历 sealed {@link LlmContent} 子类。
 */
@Slf4j
public class HistoryCompactor {

    static final String SUMMARY_PREFIX = "[Conversation summary]";

    private static final String SUMMARY_SYSTEM =
            "You are a conversation summarizer. Output a concise factual summary, no preamble.";

    private static final int SUMMARY_MAX_TOKENS = 1000;

    private static final DateTimeFormatter TRANSCRIPT_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final CompactConfig config;
    private final LlmClient client;
    private final String model;
    private final ObjectMapper json;

    public HistoryCompactor(CompactConfig config, LlmClient client, String model) {
        this.config = config;
        this.client = client;
        this.model = model;
        this.json = JsonMappers.newMapper();
    }

    /**
     * 摘要压缩。返回 true 表示成功摘要 + 替换。
     */
    public boolean apply(List<LlmMessage> messages) {
        int total = messages.size();
        int headEnd = config.summaryHeadKeep();
        int tailStart = total - config.summaryTailKeep();

        if (headEnd + 1 >= tailStart) {
            log.warn("[Compact L4] not enough room to summarize: head={} tail={} total={}",
                    headEnd, config.summaryTailKeep(), total);
            return false;
        }

        headEnd = MessageBoundary.adjustHeadEnd(messages, headEnd);
        tailStart = MessageBoundary.adjustTailStart(messages, tailStart);
        if (headEnd + 1 >= tailStart) {
            log.warn("[Compact L4] boundary protection collapsed range: head={} tail={}",
                    headEnd, tailStart);
            return false;
        }

        List<LlmMessage> middle = new ArrayList<>(messages.subList(headEnd, tailStart));
        Path archive = archiveMiddle(middle);
        if (archive == null) {
            return false;
        }

        String prevSummary = extractPreviousSummary(middle);

        String summary;
        try {
            summary = callLlmForSummary(middle, prevSummary);
        } catch (Exception e) {
            log.warn("[Compact L4] LLM summary call failed: {}", e.toString());
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("[Compact L4] LLM returned empty summary");
            return false;
        }

        String summaryMessage = SUMMARY_PREFIX + " (" + middle.size() + " messages archived to "
                + archive.toAbsolutePath() + "): " + summary;
        List<LlmMessage> rebuilt = new ArrayList<>(messages.subList(0, headEnd));
        rebuilt.add(LlmMessage.userText(summaryMessage));
        rebuilt.addAll(messages.subList(tailStart, total));

        messages.clear();
        messages.addAll(rebuilt);

        log.info("[Compact L4] history summarized ({}): archived {} middle messages, total {} → {}, " +
                "summary len={}",
                prevSummary != null ? "update" : "fresh",
                middle.size(), total, rebuilt.size(), summary.length());
        return true;
    }

    /**
     * 扫中段看有没有上次的摘要 user message(以 {@link #SUMMARY_PREFIX} 开头)。
     */
    static String extractPreviousSummary(List<LlmMessage> middle) {
        for (LlmMessage m : middle) {
            if (m.getRole() != LlmRole.USER || m.getContent() == null) continue;
            for (LlmContent c : m.getContent()) {
                if (c instanceof LlmText t && t.getText() != null
                        && t.getText().startsWith(SUMMARY_PREFIX)) {
                    String text = t.getText();
                    int colon = text.indexOf("): ");
                    if (colon > 0 && colon + 3 < text.length()) {
                        return text.substring(colon + 3);
                    }
                    return text.substring(SUMMARY_PREFIX.length()).strip();
                }
            }
        }
        return null;
    }

    /** 归档中段到 .transcripts/transcript-<ts>.jsonl(canonical JSON),失败返回 null。*/
    private Path archiveMiddle(List<LlmMessage> middle) {
        try {
            Path dir = config.transcriptDir();
            Files.createDirectories(dir);
            String filename = "transcript-" + LocalDateTime.now().format(TRANSCRIPT_TS) + ".jsonl";
            Path file = dir.resolve(filename);
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (LlmMessage m : middle) {
                    writer.write(json.writeValueAsString(m));
                    writer.newLine();
                }
            }
            return file;
        } catch (IOException e) {
            log.warn("[Compact L4] failed to archive middle: {}", e.toString());
            return null;
        }
    }

    private String callLlmForSummary(List<LlmMessage> middle, String prevSummary) {
        String conversation = renderMiddle(middle);
        String prompt = prevSummary != null
                ? buildUpdatePrompt(prevSummary, conversation, config.summaryMaxChars())
                : buildSummaryPrompt(conversation, config.summaryMaxChars());

        LlmRequest req = LlmRequest.builderWithSystemText(SUMMARY_SYSTEM)
                .model(model)
                .maxTokens(SUMMARY_MAX_TOKENS)
                .messages(List.of(LlmMessage.userText(prompt)))
                .build();

        LlmResponse resp = client.createMessage(req);
        String text = resp.firstText();
        if (text == null) return null;

        text = text.trim();
        if (text.length() > config.summaryMaxChars()) {
            text = text.substring(0, config.summaryMaxChars()) + "...";
        }
        return text;
    }

    /**
     * 把 messages 拼成 plain text 供 LLM 摘要。
     *
     * <p>P2 Step G:role → 分派模板,遍历 canonical sealed content:
     * <ul>
     *   <li>TOOL role → 每个 {@link LlmToolResult} 输出 {@code tool_result(...)}</li>
     *   <li>ASSISTANT + LlmToolCall → {@code tool_use(name)}</li>
     *   <li>LlmText / LlmThinking → 直接输出文本</li>
     *   <li>LlmOpaque → 输出 {@code <vendor:type>} 占位</li>
     * </ul>
     */
    private static String renderMiddle(List<LlmMessage> middle) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : middle) {
            String rolePrefix = switch (m.getRole()) {
                case USER -> "[user]";
                case ASSISTANT -> "[assistant]";
                case TOOL -> "[tool]";
            };
            sb.append(rolePrefix).append(' ');
            if (m.getContent() != null) {
                for (LlmContent c : m.getContent()) {
                    if (c instanceof LlmText t) {
                        sb.append(t.getText()).append(' ');
                    } else if (c instanceof LlmToolCall tc) {
                        sb.append("tool_use(").append(tc.getName()).append(") ");
                    } else if (c instanceof LlmToolResult tr) {
                        String s = tr.getOutput() != null ? tr.getOutput() : "";
                        if (s.length() > 200) s = s.substring(0, 200) + "...";
                        sb.append("tool_result(").append(s).append(") ");
                    } else if (c instanceof LlmThinking th) {
                        String s = th.getText() != null ? th.getText() : "";
                        if (s.length() > 200) s = s.substring(0, 200) + "...";
                        sb.append("thinking(").append(s).append(") ");
                    } else if (c instanceof LlmOpaque op) {
                        sb.append('<').append(op.getVendor()).append(':').append(op.getType()).append('>');
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String buildSummaryPrompt(String conversation, int maxChars) {
        return "Summarize the following agent conversation in <= " + maxChars + " characters. " +
                "Focus on:\n" +
                "1. What task is the agent working on?\n" +
                "2. What tools were called, with what outcomes?\n" +
                "3. What's the current state / what's left to do?\n\n" +
                "Output a single paragraph, no bullet points, no preamble.\n\n" +
                "<conversation>\n" + conversation + "</conversation>";
    }

    private static String buildUpdatePrompt(String prevSummary, String conversation, int maxChars) {
        return "You are updating an existing conversation summary with new content. " +
                "Output a single updated summary in <= " + maxChars + " characters. " +
                "Build ON TOP OF the existing summary —— do NOT discard facts already captured, " +
                "but REVISE / EXTEND it with anything new from the conversation. " +
                "Focus on:\n" +
                "1. What task is the agent working on?\n" +
                "2. What tools were called, with what outcomes?\n" +
                "3. What's the current state / what's left to do?\n\n" +
                "Output a single paragraph, no bullet points, no preamble.\n\n" +
                "<existing_summary>\n" + prevSummary + "\n</existing_summary>\n\n" +
                "<new_conversation>\n" + conversation + "\n</new_conversation>";
    }
}
