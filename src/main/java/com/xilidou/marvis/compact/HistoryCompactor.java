package com.xilidou.marvis.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.marvis.config.JacksonConfig;
import com.xilidou.marvis.http.AnthropicClient;
import com.xilidou.marvis.http.dto.ContentBlock;
import com.xilidou.marvis.http.dto.CreateMessageRequest;
import com.xilidou.marvis.http.dto.CreateMessageResponse;
import com.xilidou.marvis.http.dto.MessageParam;
import com.xilidou.marvis.http.dto.TextBlock;
import com.xilidou.marvis.http.dto.ToolResultBlock;
import com.xilidou.marvis.http.dto.ToolUseBlock;
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
 * <p>策略:
 * <ol>
 *   <li>保头 {@code summaryHeadKeep} 条 + 保尾 {@code summaryTailKeep} 条</li>
 *   <li>中段 messages 在被替换前,落盘到 {@code <transcriptDir>/transcript-<时间戳>.jsonl}
 *       (lossy 摘要的兜底)</li>
 *   <li>调 LLM,structured prompt 限制摘要 ≤ {@code summaryMaxChars} 字符</li>
 *   <li>用 {@code MessageParam.user("[Conversation summary: ...]")} 替换中段</li>
 * </ol>
 *
 * <p>触发时机(reactive 而非 proactive):
 * <ul>
 *   <li>L1/L2/L3 是每轮跑的预防性压缩,不消耗 API token</li>
 *   <li>L4 调 LLM = 消耗 token,每轮都跑会成本失控</li>
 *   <li>所以 L4 只在 {@link com.xilidou.marvis.http.AnthropicException#isPromptTooLong()}
 *       触发的应急路径里调用,平时不动</li>
 * </ul>
 *
 * <p>边界保护:
 * <ul>
 *   <li>{@code summaryHeadKeep + summaryTailKeep + 1 (摘要占位) >= messages.size()} →
 *       不动(没法再压了,L4 兜底失败,异常上抛让外层处理)</li>
 *   <li>tool_use ↔ tool_result 配对:摘要前 archive 写完整原文,
 *       摘要后用 {@link MessageBoundary#adjustHeadEnd}/{@link MessageBoundary#adjustTailStart}
 *       推切口避免拆配对</li>
 *   <li>LLM 调用失败:catch 异常,messages 保持不变,返回 false。
 *       调用方(reactive 路径)看到 false 就抛出原错误,不再自我循环</li>
 * </ul>
 *
 * <p>不做的事(留给后续 session):
 * <ul>
 *   <li>不做 token-aware 切口选择(用 messages.size() 切,不看每条 token)</li>
 *   <li>不做 streaming 摘要(messages 巨大时一次性塞进 prompt 也可能超限)</li>
 *   <li>不做摘要质量评估(信任模型的摘要)</li>
 *   <li>不做 .transcripts/ 的 GC(永久累积,需要外部清理)</li>
 * </ul>
 */
@Slf4j
public class HistoryCompactor {

    /** 摘要消息的前缀,用于幂等性识别。*/
    static final String SUMMARY_PREFIX = "[Conversation summary]";

    /** 调摘要时给 LLM 的 system prompt(不污染主 system prompt)。*/
    private static final String SUMMARY_SYSTEM =
            "You are a conversation summarizer. Output a concise factual summary, no preamble.";

    /** 摘要 max_tokens(给 LLM 调用时用)。*/
    private static final int SUMMARY_MAX_TOKENS = 1000;

    /** 摘要时用的模型 ID(调用方注入,通常跟主 agent 用同一个)。*/
    private static final DateTimeFormatter TRANSCRIPT_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final CompactConfig config;
    private final AnthropicClient client;
    private final String model;
    private final ObjectMapper json;

    /**
     * @param config 配置(L4 字段:summaryHeadKeep, summaryTailKeep, transcriptDir, summaryMaxChars)
     * @param client LLM 客户端(摘要调用走它)
     * @param model  摘要用模型 ID(通常跟主 agent 同一个)
     */
    public HistoryCompactor(CompactConfig config, AnthropicClient client, String model) {
        this.config = config;
        this.client = client;
        this.model = model;
        this.json = JacksonConfig.newMapper();
    }

    /**
     * 摘要压缩。返回 true 表示成功摘要 + 替换。
     *
     * <p>失败场景(返回 false):
     * <ul>
     *   <li>messages 太短(head + tail + 1 >= size,没法再压)</li>
     *   <li>LLM 调用抛异常(network / 401 / 还是 prompt_too_long)</li>
     *   <li>归档落盘失败(谨慎起见也不替换)</li>
     * </ul>
     *
     * @param messages 对话历史(原地修改)
     * @return 是否成功
     */
    public boolean apply(List<MessageParam> messages) {
        int total = messages.size();
        int headEnd = config.summaryHeadKeep();
        int tailStart = total - config.summaryTailKeep();

        // 兜底:头 + 摘要占位 + 尾 >= total → 没空间
        if (headEnd + 1 >= tailStart) {
            log.warn("[Compact L4] not enough room to summarize: head={} tail={} total={}",
                    headEnd, config.summaryTailKeep(), total);
            return false;
        }

        // 边界保护:tool_use ↔ tool_result 配对(跟 SnipCompactor 同一套规则)
        headEnd = MessageBoundary.adjustHeadEnd(messages, headEnd);
        tailStart = MessageBoundary.adjustTailStart(messages, tailStart);
        if (headEnd + 1 >= tailStart) {
            log.warn("[Compact L4] boundary protection collapsed range: head={} tail={}",
                    headEnd, tailStart);
            return false;
        }

        // 1) 归档(摘要前先存档,以防摘要丢信息)
        List<MessageParam> middle = new ArrayList<>(messages.subList(headEnd, tailStart));
        Path archive = archiveMiddle(middle);
        if (archive == null) {
            // 归档失败:不替换,以免数据丢失
            return false;
        }

        // 2) 调 LLM 摘要
        String summary;
        try {
            summary = callLlmForSummary(middle);
        } catch (Exception e) {
            log.warn("[Compact L4] LLM summary call failed: {}", e.toString());
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("[Compact L4] LLM returned empty summary");
            return false;
        }

        // 3) 替换中段
        String summaryMessage = SUMMARY_PREFIX + " (" + middle.size() + " messages archived to "
                + archive.toAbsolutePath() + "): " + summary;
        List<MessageParam> rebuilt = new ArrayList<>(messages.subList(0, headEnd));
        rebuilt.add(MessageParam.user(summaryMessage));
        rebuilt.addAll(messages.subList(tailStart, total));

        messages.clear();
        messages.addAll(rebuilt);

        log.info("[Compact L4] history summarized: archived {} middle messages, total {} → {}, " +
                "summary len={}",
                middle.size(), total, rebuilt.size(), summary.length());
        return true;
    }

    /** 归档中段到 .transcripts/transcript-<ts>.jsonl,失败返回 null。*/
    private Path archiveMiddle(List<MessageParam> middle) {
        try {
            Path dir = config.transcriptDir();
            Files.createDirectories(dir);
            String filename = "transcript-" + LocalDateTime.now().format(TRANSCRIPT_TS) + ".jsonl";
            Path file = dir.resolve(filename);
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (MessageParam m : middle) {
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

    /**
     * 把中段对话拼成结构化 prompt,调 LLM 拿摘要文本。
     *
     * <p>关键:不直接把 messages 塞进新请求的 messages 字段(那会让 LLM 当成对话延续来推理,
     * 触发 tool_use 等行为)。改用 user message 包裹完整对话文本,LLM 把它当数据看。
     */
    private String callLlmForSummary(List<MessageParam> middle) {
        String conversation = renderMiddle(middle);
        String prompt = buildSummaryPrompt(conversation, config.summaryMaxChars());

        CreateMessageRequest req = CreateMessageRequest.builder()
                .model(model)
                .maxTokens(SUMMARY_MAX_TOKENS)
                .system(SUMMARY_SYSTEM)
                .messages(List.of(MessageParam.user(prompt)))
                .build();

        CreateMessageResponse resp = client.createMessage(req);
        String text = resp.firstText();
        if (text == null) return null;

        // 截断到 summaryMaxChars(模型可能不严格遵守约束)
        text = text.trim();
        if (text.length() > config.summaryMaxChars()) {
            text = text.substring(0, config.summaryMaxChars()) + "...";
        }
        return text;
    }

    /** 把 messages 拼成 plain text。每条形如:`[user] hello\n[assistant] tool_use(read_file)`。*/
    private static String renderMiddle(List<MessageParam> middle) {
        StringBuilder sb = new StringBuilder();
        for (MessageParam m : middle) {
            sb.append('[').append(m.getRole()).append("] ");
            Object content = m.getContent();
            if (content instanceof String s) {
                sb.append(s);
            } else if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof TextBlock tb) {
                        sb.append(tb.getText()).append(' ');
                    } else if (b instanceof ToolUseBlock tub) {
                        sb.append("tool_use(").append(tub.getName()).append(") ");
                    } else if (b instanceof ToolResultBlock trb) {
                        String s = String.valueOf(trb.getContent());
                        // 截断每条 tool_result 防止 prompt 自己爆
                        if (s.length() > 200) s = s.substring(0, 200) + "...";
                        sb.append("tool_result(").append(s).append(") ");
                    } else if (b instanceof ContentBlock cb) {
                        sb.append('<').append(cb.getType()).append('>');
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
}
