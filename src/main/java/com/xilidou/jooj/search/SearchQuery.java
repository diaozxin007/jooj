package com.xilidou.jooj.search;

/**
 * SearchStore.search 的入参 —— 一行 FTS5 查询的全部参数。
 *
 * <p>所有 filter 字段都是可选(null = 不过滤),query 必填且非空。
 *
 * @param query     FTS5 MATCH 表达式(支持 bare keyword,SearchStore 内部转义)
 * @param sessionId 锁定到某一条 session(null = 跨所有 session)
 * @param role      锁定 {@code user} 或 {@code assistant}(null = 全部)
 * @param kind      锁定 {@code text} 或 {@code tool_result}(null = 全部)
 * @param limit     最多返回多少条(SearchStore 内部 clamp 到 maxLimit)
 */
public record SearchQuery(
        String query,
        String sessionId,
        String role,
        String kind,
        int limit
) {
    public SearchQuery {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
    }

    /** 简便构造:跨 session 全字段不过滤,只 limit。 */
    public static SearchQuery of(String query, int limit) {
        return new SearchQuery(query, null, null, null, limit);
    }
}
