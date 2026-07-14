package com.xilidou.jooj.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP server 目录 —— 静态配置 + 运行时状态的统一入口,替代 {@link McpProperties#getServers}。
 *
 * <p>参考 [[Jooj项目_Skill运行时加载_方案讨论]] 的 {@code SkillRegistry} 蓝本:
 * 目录扫描 + 1s 节流 rescan + {@code synchronized} 简单锁。
 *
 * <h3>启动流程</h3>
 *
 * <ol>
 *   <li>从 {@link McpServersJsonStore#loadAll} 读 {@code ~/.jooj/mcp-servers/*.json} 加载已有记录</li>
 *   <li>{@link #seedFromYml} 把 yml 里存在但 JSON 目录里没有的 server 落盘一次
 *       —— 之后 yml 不再是 source-of-truth</li>
 * </ol>
 *
 * <h3>为什么 seed 只做一次</h3>
 *
 * <p>用户在运行时通过 UI/Tool 修改的 status/enabled 会写回 JSON。如果每次启动都用 yml
 * 覆盖 JSON,这些运行时状态会丢。所以 JSON 存在时就跳过 yml —— yml 只在**首次启动**
 * 时把用户的历史 yml 配置引导过来。
 *
 * <h3>线程安全</h3>
 *
 * <p>所有对 {@code records} 的读写方法都 {@code synchronized}。参照 SkillRegistry,
 * regsitry 规模小(个位数~几十),不做读写分离 / CAS。
 *
 * <h3>历史</h3>
 *
 * <p>M1 (2026-07-14):从 {@link McpProperties#getServers} 拆出,作为 MCP 运行时加载的
 * 后端骨架。M3 加 Tool 入口,M4 加 Web UI。
 */
@Component
@Slf4j
public class McpServerRegistry {

    /** rescan 节流:1s 内重复调返回 cached size,除非 force=true。 */
    private static final long RESCAN_THROTTLE_MS = 1000;

    private final McpServersJsonStore store;
    private final Map<String, McpServerRecord> records = new LinkedHashMap<>();
    private volatile long lastScanMs = 0L;

    /**
     * Spring 注入:启动时从 JSON 目录加载 + seed from yml。
     *
     * <p>seedFromYml 会阻塞启动直到所有 seed 完成写盘 —— 通常几十毫秒(个位数 server)。
     *
     * @throws IOException seed 写盘失败(启动就中止,防止后续读到不一致状态)
     */
    public McpServerRegistry(McpProperties props, McpServersJsonStore store) throws IOException {
        this.store = store;
        loadFromDisk();
        seedFromYml(props);
        this.lastScanMs = System.currentTimeMillis();
        log.info("McpServerRegistry initialized: {} servers loaded from {}",
                records.size(), store.getDir());
    }

    // ── 加载 / seed ───────────────────────────────────────────

    private synchronized void loadFromDisk() {
        for (McpServerRecord r : store.loadAll()) {
            records.put(r.name(), r);
        }
    }

    /**
     * 把 yml 里存在但 JSON 目录里没有的 server 落盘一次(源自 yml → JSON 一次性引导)。
     *
     * <p>已存在 JSON 的 name 跳过 —— 保护用户运行时改动。
     */
    private synchronized void seedFromYml(McpProperties props) throws IOException {
        if (props.getServers() == null || props.getServers().isEmpty()) return;
        int seeded = 0;
        for (Map.Entry<String, McpProperties.Server> e : props.getServers().entrySet()) {
            String name = e.getKey();
            if (records.containsKey(name)) {
                log.debug("Skip yml seed for '{}': JSON already exists", name);
                continue;
            }
            McpServerRecord record = McpServerRecord.fromYml(name, e.getValue());
            store.save(record);
            records.put(name, record);
            log.info("Seeded MCP server '{}' from yml -> {}/{}.json",
                    name, store.getDir(), name);
            seeded++;
        }
        if (seeded > 0) {
            log.info("Seeded {} MCP server(s) from yml (first-time only)", seeded);
        }
    }

    // ── 查询 API(给 SdkStdioMcpTransport / McpRegistry 用)────

    /** 按 name 查一条记录,不存在返 empty。 */
    public synchronized Optional<McpServerRecord> get(String name) {
        return Optional.ofNullable(records.get(name));
    }

    /** name 是否已注册(等价 {@code get(name).isPresent()},但省一个 Optional 分配)。 */
    public synchronized boolean contains(String name) {
        return records.containsKey(name);
    }

    /** 所有已注册 server 的 name 列表(保序,插入序)。 */
    public synchronized List<String> listNames() {
        return List.copyOf(records.keySet());
    }

    /** 所有已注册记录(保序,插入序)—— 供 Web UI list 用。 */
    public synchronized List<McpServerRecord> list() {
        return List.copyOf(records.values());
    }

    public synchronized int size() {
        return records.size();
    }

    // ── 状态更新 API(给 McpRegistry.connect 用)──────────────

    /**
     * 标记 CONNECTED —— 更新 status 和 lastConnectedAt。name 不存在时静默(不应该发生,
     * 但避免 caller 需要提前 contains 检查)。
     */
    public synchronized void markConnected(String name) {
        McpServerRecord r = records.get(name);
        if (r == null) {
            log.warn("markConnected: server '{}' not in registry", name);
            return;
        }
        McpServerRecord updated = r.withStatus(McpServerRecord.Status.CONNECTED, null);
        records.put(name, updated);
        persistQuiet(updated);
    }

    /**
     * 标记 FAILED —— 更新 status 和 lastError。lastConnectedAt 保持原值(便于 UI 显示
     * "上次成功于 X 前")。
     */
    public synchronized void markFailed(String name, String reason) {
        McpServerRecord r = records.get(name);
        if (r == null) {
            log.warn("markFailed: server '{}' not in registry", name);
            return;
        }
        McpServerRecord updated = r.withStatus(McpServerRecord.Status.FAILED, reason);
        records.put(name, updated);
        persistQuiet(updated);
    }

    // ── rescan(为 M4 REST /api/mcp/rescan 预留;M1 阶段接口就位不接入)──

    /**
     * 强制重扫 JSON 目录,刷新 in-memory registry。
     *
     * <p>参照 {@code SkillRegistry.rescan}:
     * <ul>
     *   <li>{@code force=false} 时,1s 内重复调返回 cached size,不落盘 IO</li>
     *   <li>{@code force=true} 无条件扫盘</li>
     * </ul>
     *
     * <p>{@code synchronized} 保证 rescan 期间其他线程读方法会等,但 scan 通常 &lt; 50ms,
     * 延迟可忽略。
     *
     * @param force 跳过节流
     * @return 扫描后的 server 总数(节流命中返回当前 cached size)
     */
    public synchronized int rescan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastScanMs) < RESCAN_THROTTLE_MS) {
            return records.size();
        }
        records.clear();
        loadFromDisk();
        lastScanMs = now;
        log.info("McpServerRegistry rescan: {} servers", records.size());
        return records.size();
    }

    // ── 内部 ──────────────────────────────────────────────────

    /** 持久化失败仅 log.warn —— in-memory 状态已经更新,下次调 markXxx 会再试;不阻塞 caller。 */
    private void persistQuiet(McpServerRecord record) {
        try {
            store.save(record);
        } catch (IOException e) {
            log.warn("Failed to persist MCP server '{}' status: {}", record.name(), e.getMessage());
        }
    }
}
