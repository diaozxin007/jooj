package com.xilidou.marvis.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link ProtocolRegistry} 的状态机 + 类型校验行为。
 *
 * <p>覆盖:
 * <ul>
 *   <li>register 分配 req_<6位> id + 初始 pending</li>
 *   <li>match 正常路径 pending → approved/rejected</li>
 *   <li>match 类型不匹配防误处理(shutdown 不能被 plan_approval_response 改)</li>
 *   <li>duplicate response 跳过(已 approved 不能再被 reject)</li>
 *   <li>未知 request_id 不抛</li>
 *   <li>blank request_id 不抛</li>
 * </ul>
 */
class ProtocolRegistryTest {

    private ProtocolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProtocolRegistry();
    }

    @Test
    @DisplayName("register 分配 req_<6位> id,初始 status=pending")
    void register_returns_req_id_and_pending() {
        String id = registry.register(ProtocolState.TYPE_SHUTDOWN,
                "lead", "alice", "");
        assertTrue(id.startsWith("req_"), "id 应以 req_ 开头,实际:" + id);
        assertEquals(10, id.length(), "形如 req_XXXXXX,长度 10");

        ProtocolState state = registry.get(id);
        assertNotNull(state);
        assertEquals(ProtocolState.PENDING, state.getStatus());
        assertEquals("lead", state.getSender());
        assertEquals("alice", state.getTarget());
        assertEquals(ProtocolState.TYPE_SHUTDOWN, state.getType());
    }

    @Test
    @DisplayName("match 正常 approve:pending → approved")
    void match_approve_path() {
        String id = registry.register(ProtocolState.TYPE_SHUTDOWN, "lead", "alice", "");
        ProtocolState matched = registry.match("shutdown_response", id, true);

        assertNotNull(matched);
        assertEquals(ProtocolState.APPROVED, matched.getStatus());
        // 状态在 registry 里也更新
        assertEquals(ProtocolState.APPROVED, registry.get(id).getStatus());
    }

    @Test
    @DisplayName("match 正常 reject:pending → rejected")
    void match_reject_path() {
        String id = registry.register(ProtocolState.TYPE_PLAN_APPROVAL,
                "alice", "lead", "refactor auth");
        ProtocolState matched = registry.match("plan_approval_response", id, false);

        assertEquals(ProtocolState.REJECTED, matched.getStatus());
    }

    @Test
    @DisplayName("match 类型不匹配:shutdown 请求被 plan_approval_response 命中应忽略")
    void match_type_mismatch_ignored() {
        String id = registry.register(ProtocolState.TYPE_SHUTDOWN, "lead", "alice", "");
        ProtocolState matched = registry.match("plan_approval_response", id, true);

        assertNull(matched, "类型不匹配应返回 null");
        assertEquals(ProtocolState.PENDING, registry.get(id).getStatus(),
                "状态不应被改");
    }

    @Test
    @DisplayName("match duplicate:已 approved 的请求再 match 应被忽略")
    void match_duplicate_ignored() {
        String id = registry.register(ProtocolState.TYPE_SHUTDOWN, "lead", "alice", "");

        ProtocolState first = registry.match("shutdown_response", id, true);
        assertEquals(ProtocolState.APPROVED, first.getStatus());

        // 第二次 match(模拟 duplicate response)— 这次想 reject
        ProtocolState second = registry.match("shutdown_response", id, false);
        assertNull(second, "duplicate 应返回 null");
        assertEquals(ProtocolState.APPROVED, registry.get(id).getStatus(),
                "状态应保持 approved 不被改");
    }

    @Test
    @DisplayName("match 未知 request_id 不抛,返回 null")
    void match_unknown_request_id() {
        ProtocolState matched = registry.match("shutdown_response", "req_999999", true);
        assertNull(matched);
    }

    @Test
    @DisplayName("match blank request_id 不抛,返回 null")
    void match_blank_request_id() {
        assertNull(registry.match("shutdown_response", "", true));
        assertNull(registry.match("shutdown_response", null, true));
    }

    @Test
    @DisplayName("size + clear 行为")
    void size_and_clear() {
        assertEquals(0, registry.size());
        registry.register(ProtocolState.TYPE_SHUTDOWN, "lead", "alice", "");
        registry.register(ProtocolState.TYPE_PLAN_APPROVAL, "bob", "lead", "plan");
        assertEquals(2, registry.size());

        registry.clear();
        assertEquals(0, registry.size());
        assertEquals(0, registry.list().size());
    }

    @Test
    @DisplayName("list 返回所有 in-flight 请求(approved 后仍在,只有 clear 才删)")
    void list_includes_resolved() {
        String id = registry.register(ProtocolState.TYPE_SHUTDOWN, "lead", "alice", "");
        registry.match("shutdown_response", id, true);

        // marvis 当前不删除 approved,只是改 status — list 仍有
        assertEquals(1, registry.list().size());
        assertEquals(ProtocolState.APPROVED, registry.list().get(0).getStatus());
    }
}
