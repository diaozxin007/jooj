package com.xilidou.jooj.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session 全文搜索(s21 Demo 25)的 yml → Java 桥接。
 *
 * <p>三分法(参见 [[Jooj项目_配置架构重构_规划]] D-05):
 * <ul>
 *   <li>{@link SearchProperties}(本类)—— {@code @ConfigurationProperties("jooj.search")}</li>
 *   <li>{@link SearchConfig} —— 运行时 POJO,dbPath 已解析成 jooj home 下绝对路径</li>
 *   <li>{@link SearchConfiguration} —— {@code @Bean} 装配</li>
 * </ul>
 *
 * <p>JSON 仍是 history 的 source-of-truth({@code ~/.jooj/sessions/&lt;id&gt;.json}),
 * SQLite 是衍生 view —— SessionService.saveHistory 主流程同步双写,失败 warn 不挡 JSON 主流程。
 * SQLite 损坏可重建({@link SearchService#rebuildAll} 入口扫所有 JSON 重灌 FTS5)。
 *
 * <h3>FTS5 schema</h3>
 *
 * <p>contentful 单 virtual table:索引列 {@code content},UNINDEXED 元数据列
 * {@code session_id / msg_index / block_index / role / kind / tool_name / tool_use_id / saved_at}。
 * tokenize 用 {@code unicode61 remove_diacritics 2}(Hermes 同款,中文按字切英文不词干)。
 *
 * <p><b>历史</b>:2026-07-14 从 {@code JoojProperties.Search} 拆出,前缀 {@code jooj.search} 保持不变。
 */
@Data
@ConfigurationProperties("jooj.search")
public class SearchProperties {

    /**
     * SQLite 数据库文件名,放在 {@link com.xilidou.jooj.bootstrap.JoojHome} 下。
     * 默认 {@code search.db}。
     */
    private String dbFilename = "search.db";

    /**
     * Schema 版本 —— SearchStore.ensureSchema 启动期校验
     * {@code schema_meta.version},不匹配走 startupCheck 策略。
     * 改 schema 时 +1。
     */
    private int schemaVersion = 1;

    /** session_search tool 默认 limit(LLM 不传 limit 时使用)。 */
    private int defaultLimit = 10;

    /** session_search tool 最大 limit clamp(防 LLM 传超大值压垮 LLM 输出)。 */
    private int maxLimit = 50;

    /** SQLite busy timeout(毫秒)—— 写并发时其他连接等的最长时间。WAL 模式下基本用不到。 */
    private int busyTimeoutMs = 5000;

    /**
     * 启动期一致性检查模式:
     * <ul>
     *   <li>{@code none} — 不查,直接用现有 db</li>
     *   <li>{@code light}(默认)— 查 schema_meta.version,不一致 → drop + recreate(空索引,
     *       <b>不自动重建数据</b>),log warn 提示用户必要时调 rebuildAll API</li>
     *   <li>{@code strict} — 遍历所有 session 对 countSession(sid) vs JSON 中可索引 message 数</li>
     * </ul>
     */
    private String startupCheck = "light";
}
