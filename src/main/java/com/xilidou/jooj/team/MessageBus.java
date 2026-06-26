package com.xilidou.jooj.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MessageBus —— 文件邮箱形态的 agent 间通信,严格对齐上游 s15
 * [s15_agent_teams/code.py] 的 {@code MessageBus} 类。
 *
 * <h3>磁盘格式</h3>
 *
 * <p>每个 agent 一个 {@code <name>.jsonl} 文件,每行一条 JSON 消息:
 * <pre>
 *   .mailboxes/lead.jsonl:
 *   {"from":"alice","to":"lead","content":"Schema done","type":"result","ts":1729000000123}
 *   {"from":"bob","to":"lead","content":"Client written","type":"result","ts":1729000001456}
 *
 *   .mailboxes/alice.jsonl:
 *   {"from":"lead","to":"alice","content":"please retry","type":"message","ts":...}
 * </pre>
 *
 * <h3>语义</h3>
 *
 * <ul>
 *   <li>{@link #send}:append 一行 JSON 到对方邮箱(创建文件如果不存在)</li>
 *   <li>{@link #readInbox}:读完整个文件 → 解析所有行 → <b>删除文件</b>(消费式)</li>
 * </ul>
 *
 * <h3>跟上游 Python 比的差异</h3>
 *
 * <p>Python 上游的 {@code read_inbox} 有个**已知 race**:read_text 和 unlink 之间
 * 如果别的线程 send 了新消息,unlink 把那条消息也删了 → **丢消息**。
 * jooj 用 {@code mailboxLocks} 做 per-agent {@link ReentrantLock} 把 send 和
 * read_inbox 原子化串行,**进程内没有这个 race**。
 *
 * <p>跨进程仍然不安全(两个 jooj 进程同时操作同一份 mailbox)—— 上游教学版
 * 也接受这个限制,真实 CC 用 {@code proper-lockfile} 解决。教学版当前定位是
 * 单 jooj 进程内多 thread。
 *
 * <h3>容错</h3>
 *
 * <ul>
 *   <li>邮箱不存在 → {@link #readInbox} 返空 list,不抛</li>
 *   <li>损坏的 JSON 行 → 跳过 + warn,不让一行坏数据卡死整个 inbox</li>
 *   <li>send 时父目录不存在 → 自动创建</li>
 * </ul>
 */
@Slf4j
public class MessageBus {

    private final TeamConfig config;
    private final ObjectMapper json;

    /**
     * Per-agent {@link ReentrantLock},保护 send/readInbox 在同一 mailbox 上原子化。
     *
     * <p>{@link ConcurrentHashMap#computeIfAbsent} 安全建立锁,
     * key = agent name(对应 mailbox 文件名)。
     */
    private final Map<String, ReentrantLock> mailboxLocks = new ConcurrentHashMap<>();

    public MessageBus(TeamConfig config, ObjectMapper json) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (json == null) throw new IllegalArgumentException("json must not be null");
        this.config = config;
        this.json = json;
    }

    // ─────────────────────────────────────────────────────────────
    //  API
    // ─────────────────────────────────────────────────────────────

    /**
     * 发一条消息到 {@code toAgent} 的邮箱。
     *
     * <p>append 一行 JSON 到 {@code <toAgent>.jsonl}。文件不存在时创建,父目录不存在时也创建。
     *
     * @param fromAgent 发送者 name
     * @param toAgent   接收者 name(对应文件名)
     * @param content   文本内容
     * @param type      消息类型({@code "message"} / {@code "result"} 等),null 默认 {@code "message"}
     * @return 写入的 {@link Message} 对象,带分配的 ts
     */
    public Message send(String fromAgent, String toAgent, String content, String type) {
        return send(fromAgent, toAgent, content, type, null);
    }

    /**
     * s16 增强版:发消息时附 metadata(协议字段如 request_id / approve)。
     *
     * <p>跟 4 参版唯一差别是 metadata 会写入 Message.metadata,jsonl 序列化时
     * 出现 {@code "metadata": {"request_id": "..."}} 字段。
     *
     * @param metadata 协议附加字段。null 或空 map 都视为"无 metadata"
     * @return 写入磁盘的 {@link Message} 对象
     */
    public Message send(String fromAgent, String toAgent, String content, String type,
                        Map<String, Object> metadata) {
        validateName(fromAgent, "fromAgent");
        validateName(toAgent, "toAgent");
        if (content == null) content = "";
        if (type == null || type.isBlank()) type = "message";

        long ts = System.currentTimeMillis();
        Map<String, Object> meta = metadata != null
                ? new HashMap<>(metadata)
                : new HashMap<>();
        Message msg = new Message(fromAgent, toAgent, content, type, ts, meta);

        ReentrantLock lock = lockFor(toAgent);
        lock.lock();
        try {
            Path file = mailboxFile(toAgent);
            Files.createDirectories(file.toAbsolutePath().getParent());
            String line = json.writeValueAsString(msg) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("[Bus] {} → {} ({}, {} chars, meta={})",
                    fromAgent, toAgent, type, content.length(),
                    meta.isEmpty() ? "{}" : meta.keySet());
            return msg;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to send message to " + toAgent, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读 {@code agent} 的邮箱,返回所有消息,然后删除邮箱文件(消费式)。
     *
     * <p>跟上游 {@code read_inbox} 一致 —— 这是"取走"语义,不是"看一眼"。
     *
     * @return 按时间序的消息列表;邮箱不存在 → 空 list
     */
    public List<Message> readInbox(String agent) {
        validateName(agent, "agent");

        ReentrantLock lock = lockFor(agent);
        lock.lock();
        try {
            Path file = mailboxFile(agent);
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                return List.of();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read inbox " + agent, e);
            }

            List<Message> out = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    Message m = json.readValue(line, Message.class);
                    out.add(m);
                } catch (Exception e) {
                    log.warn("[Bus] {} skipping corrupt line: {}", agent, e.toString());
                }
            }

            // 消费式:读完删邮箱文件。删除失败不抛(下次还会被读到,无非重复消费 —— 比丢消息好)
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("[Bus] failed to delete inbox file {}: {}", file, e.toString());
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 不消费的"看一眼" —— 测试/监控用。生产路径请用 {@link #readInbox}。
     *
     * @return 当前邮箱里的消息数
     */
    public int peekSize(String agent) {
        validateName(agent, "agent");
        Path file = mailboxFile(agent);
        try {
            return (int) Files.lines(file, StandardCharsets.UTF_8)
                    .filter(s -> !s.isBlank()).count();
        } catch (NoSuchFileException e) {
            return 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 邮箱目录的根。测试/调试用。 */
    public Path mailboxDir() {
        return config.mailboxDir();
    }

    // ─────────────────────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────────────────────

    private Path mailboxFile(String agent) {
        return config.mailboxDir().resolve(agent + ".jsonl");
    }

    private ReentrantLock lockFor(String agent) {
        return mailboxLocks.computeIfAbsent(agent, k -> new ReentrantLock());
    }

    /** agent name 防 path injection —— 不能含 / \ ..,跟 TaskStore.validateId 同思路。 */
    private static void validateName(String name, String fieldName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException(
                    fieldName + " must not contain path separators or '..': " + name);
        }
    }
}
