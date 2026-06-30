package com.xilidou.jooj.tool.impl;

import com.xilidou.jooj.http.dto.InputSchema;
import com.xilidou.jooj.search.SearchHit;
import com.xilidou.jooj.search.SearchQuery;
import com.xilidou.jooj.search.SearchService;
import com.xilidou.jooj.tool.Tool;
import com.xilidou.jooj.tool.ToolCall;
import com.xilidou.jooj.tool.ToolDefinition;
import com.xilidou.jooj.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionSearchTool —— LLM 跨 session 全文搜(s21 Demo 25,Hermes Tier 2 P2.4)。
 *
 * <p>跟 {@code memory} 的边界:
 * <ul>
 *   <li>memory(MemoryService catalog + selector)— **always-on**,关键事实必看,SYSTEM 注入</li>
 *   <li>session_search — **按需查**,LLM 决定调,从历史 session 找具体对话上下文</li>
 * </ul>
 *
 * <h3>schema</h3>
 *
 * <ul>
 *   <li>{@code query}(必填)— FTS5 表达式,bare keyword 也支持(如 {@code weixin})</li>
 *   <li>{@code session_id}(可选)— 锁定到一条 session</li>
 *   <li>{@code role}(可选)— {@code user} / {@code assistant}</li>
 *   <li>{@code kind}(可选)— {@code text} / {@code tool_result}</li>
 *   <li>{@code limit}(可选)— 默认 10,clamp 到 maxLimit=50</li>
 * </ul>
 *
 * <p>输出:每 hit 一行紧凑文本
 * {@code #1 [session=abc12...|user|msg=42|@2026-06-29] ...snippet with <b>highlight</b>...}。
 * 空结果返回 {@code "No hits."}。
 */
@Component
@Slf4j
public class SessionSearchTool implements Tool {

    private final SearchService searchService;

    public SessionSearchTool(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String getName() {
        return "session_search";
    }

    @Override
    public String getDescription() {
        return "Cross-session full-text search over historical conversations. " +
                "Use when user references past discussions or you need context from earlier sessions.";
    }

    @Override
    public List<ToolDefinition> getTools() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of(
                "type", "string",
                "description", "FTS5 search expression (bare keywords also work). " +
                        "Examples: 'weixin', 'cron AND session', '\"exact phrase\"'."));
        props.put("session_id", Map.of(
                "type", "string",
                "description", "Optional: limit search to one session id (sanitized form, e.g. chat_weixin_xxx)."));
        props.put("role", Map.of(
                "type", "string",
                "enum", List.of("user", "assistant"),
                "description", "Optional: filter by message role."));
        props.put("kind", Map.of(
                "type", "string",
                "enum", List.of("text", "tool_result"),
                "description", "Optional: filter by content kind. " +
                        "'text' = user/assistant text; 'tool_result' = tool execution outputs."));
        props.put("limit", Map.of(
                "type", "integer",
                "description", "Max hits (default " + searchService.defaultLimit()
                        + ", clamped to " + searchService.maxLimit() + ")."));

        return List.of(new ToolDefinition(
                "session_search",
                "Search the FTS5 index over all session histories. " +
                        "Returns compact one-line-per-hit text with <b>...</b> highlighting around matches. " +
                        "Use this when the user references past conversations or you need to recall " +
                        "what was decided in another session.",
                InputSchema.object(props, "query")
        ));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!"session_search".equals(call.getToolName())) {
            return new ToolResult(false, "Unknown tool: " + call.getToolName());
        }

        Object queryArg = call.getArguments().get("query");
        if (queryArg == null) {
            return new ToolResult(false, "Error: 'query' is required");
        }
        String query = queryArg.toString().trim();
        if (query.isEmpty()) {
            return new ToolResult(true, "No hits.");
        }

        String sessionId = optString(call, "session_id");
        String role = optString(call, "role");
        String kind = optString(call, "kind");

        int limit = searchService.defaultLimit();
        Object limitArg = call.getArguments().get("limit");
        if (limitArg instanceof Number n) {
            limit = n.intValue();
        } else if (limitArg != null) {
            try {
                limit = Integer.parseInt(limitArg.toString());
            } catch (NumberFormatException e) {
                return new ToolResult(false, "Error: 'limit' must be an integer (got: " + limitArg + ")");
            }
        }
        if (limit <= 0) limit = searchService.defaultLimit();

        SearchQuery q = new SearchQuery(query, sessionId, role, kind, limit);
        List<SearchHit> hits;
        try {
            hits = searchService.search(q);
        } catch (Exception e) {
            log.warn("[SessionSearch] search failed: {}", e.toString());
            return new ToolResult(false, "Error: search failed: " + e.getMessage());
        }

        if (hits.isEmpty()) {
            return new ToolResult(true, "No hits.");
        }

        return new ToolResult(true, format(hits));
    }

    private static String optString(ToolCall call, String name) {
        Object v = call.getArguments().get(name);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 紧凑输出:每 hit 一行
     * {@code #N [session=ABBR|role|msg=I|@DATE] snippet...}。
     * snippet 已经带 {@code <b>match</b>} 高亮(由 SQLite snippet() 函数生成)。
     */
    private static String format(List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            sb.append('#').append(i + 1)
                    .append(" [session=").append(abbreviate(h.sessionId(), 12))
                    .append('|').append(h.role() != null ? h.role() : "?")
                    .append("|msg=").append(h.msgIndex());
            if ("tool_result".equals(h.kind()) && h.toolName() != null) {
                sb.append("|tool=").append(h.toolName());
            }
            sb.append("|@").append(formatDate(h.savedAt()))
                    .append("] ");
            if (h.snippet() != null) sb.append(h.snippet());
            if (i + 1 < hits.size()) sb.append('\n');
        }
        return sb.toString();
    }

    private static String abbreviate(String s, int maxLen) {
        if (s == null) return "?";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    /** epoch millis → YYYY-MM-DD(本地时区,简洁,不含 time)。 */
    private static String formatDate(long epochMs) {
        if (epochMs <= 0) return "?";
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).toString();
    }
}
