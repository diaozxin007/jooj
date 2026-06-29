package com.xilidou.jooj.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.http.dto.MessageParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link SessionService} 的核心契约 —— framework-agnostic,
 * 直接 {@code new SessionService(...)},不走 Spring 容器。
 *
 * <p>覆盖:
 * <ul>
 *   <li>create / list / get / delete / updateTitle</li>
 *   <li>50 上限校验</li>
 *   <li>reserved session(default / cli-default / cron-default)不允许 delete</li>
 *   <li>load + save history round-trip(in-memory cache + 盘)</li>
 *   <li>ensureBootstrap 幂等 + 持久化</li>
 * </ul>
 */
class SessionServiceTest {

    @TempDir
    Path tmp;

    SessionStore store;
    SessionService service;

    @BeforeEach
    void setUp() throws IOException {
        store = new SessionStore(tmp, new ObjectMapper().findAndRegisterModules());
        service = new SessionService(store);
        service.ensureBootstrap();
    }

    @Test
    @DisplayName("ensureBootstrap 注册三个 reserved session,幂等")
    void bootstrap_creates_three_reserved_sessions_idempotently() {
        var list = service.list();
        assertTrue(list.stream().anyMatch(s -> Session.DEFAULT_ID.equals(s.id())));
        assertTrue(list.stream().anyMatch(s -> Session.CLI_DEFAULT_ID.equals(s.id())));
        assertTrue(list.stream().anyMatch(s -> Session.CRON_DEFAULT_ID.equals(s.id())));

        // 再 bootstrap 一次,数量不变
        service.ensureBootstrap();
        assertEquals(list.size(), service.list().size(), "bootstrap 应该是幂等的");
    }

    @Test
    @DisplayName("create 成功生成 UUID + 默认 title;list 包含新 session")
    void create_returns_new_session_with_uuid() {
        Session s = service.create(null);
        assertNotNull(s.id());
        assertTrue(s.id().length() > 8);
        assertNotNull(s.title());
        assertTrue(s.title().startsWith("New chat"));

        assertTrue(service.list().stream().anyMatch(x -> x.id().equals(s.id())));
    }

    @Test
    @DisplayName("create 自定义 title")
    void create_with_custom_title() {
        Session s = service.create("项目 A 调试");
        assertEquals("项目 A 调试", s.title());
    }

    @Test
    @DisplayName("get 不存在的 session 抛 NoSuchElementException")
    void get_unknown_throws() {
        assertThrows(NoSuchElementException.class, () -> service.get("non-existent"));
    }

    @Test
    @DisplayName("delete reserved session 抛 IllegalArgumentException")
    void delete_reserved_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.delete(Session.DEFAULT_ID));
        assertThrows(IllegalArgumentException.class, () -> service.delete(Session.CLI_DEFAULT_ID));
        assertThrows(IllegalArgumentException.class, () -> service.delete(Session.CRON_DEFAULT_ID));
    }

    @Test
    @DisplayName("delete 不存在 session 抛 NoSuchElementException")
    void delete_unknown_throws() {
        assertThrows(NoSuchElementException.class, () -> service.delete("non-existent"));
    }

    @Test
    @DisplayName("delete 之后 list / get 都看不到了 + history 文件被清")
    void delete_removes_session_and_history_file() {
        Session s = service.create("temp");
        // 写一条 history 触发 history 文件落盘
        List<MessageParam> hist = service.loadHistory(s.id());
        hist.add(MessageParam.user("hello"));
        service.saveHistory(s.id(), hist);

        service.delete(s.id());

        assertFalse(service.exists(s.id()));
        assertThrows(NoSuchElementException.class, () -> service.get(s.id()));

        // history 文件应被删除
        Path histFile = tmp.resolve(s.id() + ".json");
        assertFalse(java.nio.file.Files.exists(histFile),
                "delete session 后 history 文件应被清: " + histFile);
    }

    @Test
    @DisplayName("updateTitle 改 title;时间戳保持")
    void update_title() {
        Session s = service.create("old");
        Session updated = service.updateTitle(s.id(), "new");
        assertEquals("new", updated.title());
        assertEquals(s.id(), updated.id());
        assertEquals(s.createdAt(), updated.createdAt());
    }

    @Test
    @DisplayName("updateTitle 空白 title 抛 IllegalArgumentException")
    void update_blank_title_throws() {
        Session s = service.create("ok");
        assertThrows(IllegalArgumentException.class, () -> service.updateTitle(s.id(), ""));
        assertThrows(IllegalArgumentException.class, () -> service.updateTitle(s.id(), "   "));
    }

    @Test
    @DisplayName("loadHistory 自动注册未知 session")
    void load_history_auto_registers() {
        List<MessageParam> hist = service.loadHistory("auto-test");
        assertNotNull(hist);
        assertTrue(service.exists("auto-test"));
    }

    @Test
    @DisplayName("save+load history round-trip:盘上的内容能再读出来")
    void save_load_history_round_trip() {
        Session s = service.create("rt");
        List<MessageParam> hist = service.loadHistory(s.id());
        hist.add(MessageParam.user("hello"));
        hist.add(MessageParam.assistant(List.of(
                new com.xilidou.jooj.http.dto.TextBlock("world"))));
        service.saveHistory(s.id(), hist);

        // 重新构造 service,清掉 in-memory cache,模拟进程重启
        SessionStore newStore = new SessionStore(tmp,
                new ObjectMapper().findAndRegisterModules());
        SessionService newService = new SessionService(newStore);
        newService.ensureBootstrap();

        List<MessageParam> reloaded = newService.loadHistory(s.id());
        assertEquals(2, reloaded.size());
        assertEquals("user", reloaded.get(0).getRole());
        assertEquals("assistant", reloaded.get(1).getRole());
    }

    @Test
    @DisplayName("saveHistory 同步更新 messageCount + lastActiveAt")
    void save_history_updates_metadata() {
        Session s = service.create("meta");
        List<MessageParam> hist = service.loadHistory(s.id());
        hist.add(MessageParam.user("a"));
        hist.add(MessageParam.user("b"));
        hist.add(MessageParam.user("c"));
        service.saveHistory(s.id(), hist);

        Session refreshed = service.get(s.id());
        assertEquals(3, refreshed.messageCount());
        assertNotNull(refreshed.lastActiveAt());
    }

    @Test
    @DisplayName("clearHistory 清空 + 落盘空文件")
    void clear_history_resets() {
        Session s = service.create("c");
        List<MessageParam> hist = service.loadHistory(s.id());
        hist.add(MessageParam.user("hi"));
        service.saveHistory(s.id(), hist);

        service.clearHistory(s.id());

        assertEquals(0, service.loadHistory(s.id()).size());
    }

    @Test
    @DisplayName("MAX_SESSIONS 上限触发 IllegalStateException")
    void max_sessions_limit() {
        // 启动后已有 3 个 reserved,再加 47 个总数到 50
        for (int i = 0; i < SessionService.MAX_SESSIONS - 3; i++) {
            service.create("session-" + i);
        }
        assertEquals(SessionService.MAX_SESSIONS, service.list().size());

        assertThrows(IllegalStateException.class,
                () -> service.create("over the limit"));
    }

    @Test
    @DisplayName("createWithId 幂等(已存在不重复创建)")
    void create_with_id_idempotent() {
        Session a = service.createWithId("test-id", "first");
        Session b = service.createWithId("test-id", "second");
        assertEquals(a.id(), b.id());
        assertEquals("first", b.title(), "已存在时不应覆盖 title");
    }
}
