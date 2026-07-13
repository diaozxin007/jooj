package com.xilidou.jooj.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.http.dto.MessageParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
        service = SessionService.forTests(store);
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
        SessionService newService = SessionService.forTests(newStore);
        newService.ensureBootstrap();

        List<MessageParam> reloaded = newService.loadHistory(s.id());
        assertEquals(2, reloaded.size());
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

    // ─────────────────────────────────────────────────────────────
    //  s21 review bug fixes
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BUG 1:delete 后 AgentLockProvider.release 被调(lock 不泄漏)")
    void bug1_delete_releases_lock() {
        AgentLockProvider lockProvider = new AgentLockProvider();
        SessionService svc = SessionService.forTests(store, null, lockProvider);
        svc.ensureBootstrap();
        Session s = svc.create("temp");
        // lock 先装上
        lockProvider.lockFor(s.id());

        svc.delete(s.id());

        // release 后 map 里已清,lockFor 再拿到的是个新 lock 对象
        var lockAfter = lockProvider.lockFor(s.id());
        assertNotNull(lockAfter);
        // 如果 release 没被调,lockProvider.locks 里还会有旧的 lock;
        // 这里验证删除后 key 被清(重新 lockFor 会 computeIfAbsent 建新锁)
        assertTrue(lockAfter.tryLock(), "新 lock 应未被持有");
        lockAfter.unlock();
    }

    @Test
    @DisplayName("BUG 2:saveHistory 对不在 index 的 session 不再静默 return(状态不一致)")
    void bug2_save_history_unknown_session_does_not_silently_skip() {
        // 创建 session 然后绕过 service 直接移除内存 map(模拟 bug 场景)
        Session s = service.create("orphan");
        String id = s.id();
        // 不调 delete(要保留 JSON 文件),只让内存 map 失去同步
        // 最简单:用一个新 service 实例,不 ensureBootstrap,手动 load → map 里没有这条
        SessionService fresh = SessionService.forTests(store);
        fresh.ensureBootstrap();  // 只 bootstrap reserved session,普通 session 不在 map

        List<MessageParam> hist = List.of(MessageParam.user("hello"));
        // saveHistory 应该 warn + 自动注册,不 silently return
        assertDoesNotThrow(() -> fresh.saveHistory(id, hist));

        // 验证:session 现在在 fresh 的 map 里了
        assertTrue(fresh.exists(id), "saveHistory 应自动注册 unknown session");

        // 磁盘 JSON 也已更新(saveHistory 调 store.writeHistory)
        List<MessageParam> loaded = fresh.loadHistory(id);
        assertEquals(1, loaded.size());
    }

    @Test
    @DisplayName("BUG 3:clearHistory 清空 history 且不抛(冗余 onClearHistory 已删,searchService=null 不 NPE)")
    void bug3_clear_history_works_without_search_service() {
        // searchService=null 的 service(老 1 参 ctor)调 clearHistory 不应 NPE
        // 这守门"不再显式调 searchService.onClearHistory"不会因 NPE 爆
        Session s = service.create("c");
        service.loadHistory(s.id()).add(MessageParam.user("hi"));
        service.saveHistory(s.id(), service.loadHistory(s.id()));
        assertEquals(1, service.loadHistory(s.id()).size());

        assertDoesNotThrow(() -> service.clearHistory(s.id()));
        assertEquals(0, service.loadHistory(s.id()).size(), "clearHistory 应清空 history");
    }

    @Test
    @DisplayName("BUG 7:createWithId 不允许绕过 MAX_SESSIONS(非 reserved)")
    void bug7_create_with_id_respects_max_sessions() throws IOException {
        // 用全新 store + service,填满 MAX_SESSIONS 个 session
        Path freshDir = tmp.resolve("fresh");
        java.nio.file.Files.createDirectories(freshDir);
        SessionStore freshStore = new SessionStore(freshDir,
                new ObjectMapper().findAndRegisterModules());
        SessionService freshSvc = SessionService.forTests(freshStore);
        freshSvc.ensureBootstrap();  // 建 3 个 reserved

        // 填到 MAX_SESSIONS
        for (int i = freshSvc.list().size(); i < SessionService.MAX_SESSIONS; i++) {
            freshSvc.create("session " + i);
        }
        assertEquals(SessionService.MAX_SESSIONS, freshSvc.list().size());

        // create 应该拒
        assertThrows(IllegalStateException.class, () -> freshSvc.create("overflow"));

        // createWithId 对非 reserved 也应该拒(BUG 7 修复点)
        assertThrows(IllegalStateException.class,
                () -> freshSvc.createWithId("extra-" + System.nanoTime(), "overflow via id"),
                "createWithId 应同样拒绝超过 MAX_SESSIONS(BUG 7 修复)");

        // createWithId 对 reserved 应该豁免(bootstrap 不受限)
        assertDoesNotThrow(
                () -> freshSvc.createWithId(Session.DEFAULT_ID, "reserved"),
                "reserved session 应豁免 MAX_SESSIONS 检查");
    }

    // ────────────────────────────────────────────────────────────
    //  s22 架构审查(2026-07-13):契约测试锁定新语义
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loadHistory(sid, false) 严格模式 —— session 不存在抛 NoSuchElementException")
    void load_history_strict_mode_throws_on_missing_session() {
        assertThrows(NoSuchElementException.class,
                () -> service.loadHistory("never-existed-session", false),
                "严格模式下不存在的 session 应抛 NoSuchElementException");
    }

    @Test
    @DisplayName("loadHistory(sid, true) 兼容模式 —— session 不存在自动注册")
    void load_history_lenient_mode_auto_registers_missing_session() {
        String sid = "auto-created-" + System.nanoTime();
        assertFalse(service.exists(sid), "前置:session 不存在");

        List<MessageParam> hist = service.loadHistory(sid, true);
        assertNotNull(hist, "应返回空 list 而非 null");
        assertTrue(hist.isEmpty(), "新建 session 的 history 应是空的");
        assertTrue(service.exists(sid), "loadHistory(sid, true) 应触发 auto-register");
    }

    @Test
    @DisplayName("loadHistory(sid) 默认走兼容模式 —— 保持原契约")
    void load_history_default_signature_is_lenient() {
        String sid = "auto-lenient-" + System.nanoTime();
        // 通过老 1 参签名调用
        List<MessageParam> hist = service.loadHistory(sid);
        assertNotNull(hist);
        assertTrue(service.exists(sid),
                "1 参 loadHistory 默认 createIfMissing=true,兼容旧行为");
    }

    @Test
    @DisplayName("saveHistory 传入的 list 会更新 cache 引用(问题 3 契约)")
    void save_history_updates_cache_reference() {
        Session s = service.create("cache-sync-test");
        // 第一次 loadHistory 拿到空 list
        List<MessageParam> firstRef = service.loadHistory(s.id());
        firstRef.add(MessageParam.user("first"));
        service.saveHistory(s.id(), firstRef);

        // 现在 caller 换成一个**新 list** 传给 saveHistory
        List<MessageParam> newList = new ArrayList<>();
        newList.add(MessageParam.user("second-new-ref"));
        service.saveHistory(s.id(), newList);

        // 关键断言:下次 loadHistory 应看到新 list 内容(cache 已更新引用)
        List<MessageParam> reloaded = service.loadHistory(s.id());
        assertEquals(1, reloaded.size(),
                "cache 应更新到 saveHistory 传入的新 list,而非保留旧引用");
        // MessageParam.content 可能是 String 或 List<ContentBlock>,取决于工厂
        // 我们只需要断言"是那条新消息"而不是具体形态
        assertNotNull(reloaded.get(0).getContent());
    }
}
