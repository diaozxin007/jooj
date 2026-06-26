package com.xilidou.marvis.team;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 协议状态注册表 —— 严格对齐上游 s16 的 {@code pending_requests: dict[str, ProtocolState]}。
 *
 * <h3>职责</h3>
 *
 * <ul>
 *   <li>分配 {@code request_id}(形如 {@code "req_<6位随机数>"})</li>
 *   <li>维护 in-flight 协议请求的状态(pending → approved/rejected)</li>
 *   <li>{@link #match} —— 收到 response 时按 request_id 关联,**带类型校验**(防 shutdown_response
 *       误处理 plan_approval 请求)</li>
 *   <li>{@link #cancel} —— 显式取消(目前内部用,API 暴露给后续优化)</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 *
 * <p>{@link ConcurrentHashMap} 装载状态。{@link #match} 内部状态转换是 read-modify-write,
 * 用 {@link Map#computeIfPresent} 保证原子性。
 *
 * <h3>不持久化</h3>
 *
 * <p>跟上游一致 —— 协议状态是瞬态的(几秒到几分钟),marvis 进程崩溃后 pending 请求作废,
 * 不写盘。如果将来需要 "marvis 重启后恢复未响应请求",再加 {@link CronStore} 同款
 * persist/load 模式。
 *
 * @see ProtocolState
 */
@Component
@Slf4j
public class ProtocolRegistry {

    private final Map<String, ProtocolState> pending = new ConcurrentHashMap<>();

    /**
     * 分配新 request_id 并登记一条 pending 状态。
     *
     * @param type    {@link ProtocolState#TYPE_SHUTDOWN} 或 {@link ProtocolState#TYPE_PLAN_APPROVAL}
     * @param sender  请求发起方 agent name
     * @param target  请求目标方 agent name
     * @param payload 附带内容(plan 文本 / 关机原因 / 空串)
     * @return 新分配的 request_id 形如 {@code "req_004281"}
     */
    public String register(String type, String sender, String target, String payload) {
        String requestId = newRequestId();
        ProtocolState state = new ProtocolState(
                requestId, type, sender, target,
                ProtocolState.PENDING,
                payload != null ? payload : "",
                System.currentTimeMillis()
        );
        pending.put(requestId, state);
        log.info("[Protocol] registered {} {} from {} to {}",
                type, requestId, sender, target);
        return requestId;
    }

    /**
     * 匹配响应到原始请求,带类型校验 + duplicate 防护。
     *
     * <p>对应上游 {@code match_response}:
     * <ol>
     *   <li>requestId 不在 pending → log warn,不抛(防恶意请求 / 错位 retry)</li>
     *   <li>请求 type 跟响应 responseType 不匹配 → log warn,不动状态
     *       (防 shutdown_response 误处理 plan_approval 请求)</li>
     *   <li>状态已不是 pending → log warn,跳过(防 duplicate response)</li>
     *   <li>否则更新 status 为 approved 或 rejected</li>
     * </ol>
     *
     * @param responseType 响应 type 字符串(如 {@code "shutdown_response"})
     * @param requestId    待匹配的 request_id
     * @param approve      true=approved,false=rejected
     * @return 匹配后的状态(如果匹配失败返回 null)
     */
    public ProtocolState match(String responseType, String requestId, boolean approve) {
        if (requestId == null || requestId.isBlank()) {
            log.warn("[Protocol] match called with blank request_id");
            return null;
        }

        ProtocolState state = pending.get(requestId);
        if (state == null) {
            log.warn("[Protocol] unknown request_id: {}", requestId);
            return null;
        }

        // 类型校验:shutdown 请求只接受 shutdown_response,plan_approval 同理
        String expectedResponseType = state.getType() + "_response";
        if (!expectedResponseType.equals(responseType)) {
            log.warn("[Protocol] type mismatch for {}: expected {}, got {}",
                    requestId, expectedResponseType, responseType);
            return null;
        }

        // duplicate 防护:已 resolve 的请求不再处理
        if (!ProtocolState.PENDING.equals(state.getStatus())) {
            log.warn("[Protocol] {} already {}, ignoring duplicate response",
                    requestId, state.getStatus());
            return null;
        }

        // 用 computeIfPresent 保证原子的 read-modify-write
        return pending.computeIfPresent(requestId, (id, s) -> {
            // 二次检查(可能在两次读之间被别的 thread 改了)
            if (!ProtocolState.PENDING.equals(s.getStatus())) {
                return s;
            }
            s.setStatus(approve ? ProtocolState.APPROVED : ProtocolState.REJECTED);
            log.info("[Protocol] {} {} ({})",
                    s.getType(), s.getStatus(), id);
            return s;
        });
    }

    /**
     * 显式查询某请求当前状态。
     *
     * @return 不存在时返回 null
     */
    public ProtocolState get(String requestId) {
        return pending.get(requestId);
    }

    /**
     * 列出所有 in-flight 请求(只读快照)。
     */
    public List<ProtocolState> list() {
        return List.copyOf(pending.values());
    }

    /**
     * 测试 / 监控用:清空所有 pending 请求。
     * 生产路径不要调,会丢失未响应的请求状态。
     */
    public void clear() {
        pending.clear();
    }

    /** 测试用:当前 pending 数量。 */
    public int size() {
        return pending.size();
    }

    /**
     * 生成新 request_id —— 跟上游
     * {@code f"req_{random.randint(0, 999999):06d}"} 严格一致。
     *
     * <p>包级可见,允许测试通过反射观察。
     */
    String newRequestId() {
        int rand = ThreadLocalRandom.current().nextInt(1_000_000);
        return String.format("req_%06d", rand);
    }
}
