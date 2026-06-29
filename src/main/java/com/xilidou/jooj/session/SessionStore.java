package com.xilidou.jooj.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.http.dto.MessageParam;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session 的 IO 层 —— 整加载 / 整落盘,分离业务逻辑。
 *
 * <p>文件布局:
 * <pre>
 * ~/.jooj/sessions/
 *   ├── index.json                索引(id → metadata)
 *   ├── default.json              Web 默认 session 的 history
 *   ├── cli-default.json          CLI 固定 session
 *   ├── cron-default.json         cron 收容 session
 *   └── &lt;uuid&gt;.json               用户主动新建的 session
 * </pre>
 *
 * <p>每个 history 文件 = {@code List<MessageParam>} 的 Jackson serialized JSON。
 *
 * <p>不参与并发控制 —— 由 {@link SessionService} 在更上层用 lock 协调,
 * SessionStore 自己只关心"给一个 path 能读出 / 写入这个 list"。
 */
@Slf4j
public class SessionStore {

    /** 索引文件名(放在 sessions/ 目录里)。 */
    private static final String INDEX_FILE = "index.json";

    private final Path sessionsDir;
    private final ObjectMapper json;

    public SessionStore(Path sessionsDir, ObjectMapper json) {
        if (sessionsDir == null) throw new IllegalArgumentException("sessionsDir must not be null");
        if (json == null) throw new IllegalArgumentException("json must not be null");
        this.sessionsDir = sessionsDir;
        this.json = json;
    }

    public Path sessionsDir() {
        return sessionsDir;
    }

    // ── 索引 IO ────────────────────────────────────────────────

    /** 读取索引。文件不存在时返空 map(不当错误)。 */
    public Map<String, Session> readIndex() {
        Path indexPath = sessionsDir.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            return new LinkedHashMap<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(indexPath);
            if (bytes.length == 0) return new LinkedHashMap<>();
            List<Session> list = json.readValue(bytes, new TypeReference<List<Session>>() {
            });
            // 用 LinkedHashMap 保持创建顺序(便于前端 list 显示)
            Map<String, Session> map = new LinkedHashMap<>();
            for (Session s : list) {
                if (s != null && s.id() != null) {
                    map.put(s.id(), s);
                }
            }
            return map;
        } catch (IOException e) {
            log.warn("[Session] readIndex failed: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    /** 写索引。整个 map 序列化成 list,保持 LinkedHashMap 的创建顺序。 */
    public void writeIndex(Map<String, Session> sessions) {
        Path indexPath = sessionsDir.resolve(INDEX_FILE);
        try {
            Files.createDirectories(sessionsDir);
            byte[] bytes = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(new ArrayList<>(sessions.values()));
            Files.write(indexPath, bytes);
        } catch (IOException e) {
            log.warn("[Session] writeIndex failed: {}", e.toString());
        }
    }

    // ── History IO ─────────────────────────────────────────────

    /** 读 history。文件不存在 → 空 list。 */
    public List<MessageParam> readHistory(String sessionId) {
        Path path = historyPath(sessionId);
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return new ArrayList<>();
            return json.readValue(bytes, new TypeReference<List<MessageParam>>() {
            });
        } catch (IOException e) {
            log.warn("[Session] readHistory({}) failed: {}", sessionId, e.toString());
            return new ArrayList<>();
        }
    }

    /** 写 history。整个 list 序列化覆盖。 */
    public void writeHistory(String sessionId, List<MessageParam> history) {
        Path path = historyPath(sessionId);
        try {
            Files.createDirectories(sessionsDir);
            byte[] bytes = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(history != null ? history : new ArrayList<>());
            Files.write(path, bytes);
        } catch (IOException e) {
            log.warn("[Session] writeHistory({}) failed: {}", sessionId, e.toString());
        }
    }

    /** 删 history 文件(idempotent,文件不存在不抛)。 */
    public void deleteHistory(String sessionId) {
        Path path = historyPath(sessionId);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[Session] deleteHistory({}) failed: {}", sessionId, e.toString());
        }
    }

    private Path historyPath(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        // 防路径注入:只允许 [a-zA-Z0-9_-] 字符
        if (!sessionId.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("invalid sessionId: " + sessionId);
        }
        return sessionsDir.resolve(sessionId + ".json");
    }
}
