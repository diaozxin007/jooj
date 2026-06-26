package com.xilidou.jooj.hook.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.config.JacksonConfig;
import com.xilidou.jooj.http.dto.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 MetricsHook 的核心行为：
 * <ul>
 *   <li>Pre/Post 配对正常时正确累计</li>
 *   <li>失败统计准确（output 以 Error / Permission denied 开头）</li>
 *   <li>多个工具的指标互不干扰</li>
 *   <li>Pre 没记录直接来 Post 不会崩</li>
 * </ul>
 */
class MetricsHookTest {

    private static final ObjectMapper JSON = JacksonConfig.newMapper();

    private MetricsHook hook;

    @BeforeEach
    void setUp() {
        hook = new MetricsHook();
    }

    private static ToolUseBlock toolUse(String name, String id) {
        JsonNode input = JSON.valueToTree(Map.of("k", "v"));
        return new ToolUseBlock(id, name, input);
    }

    @Test
    @DisplayName("Pre + Post 配对：累计调用次数 + 平均延迟")
    void records_call_and_latency() throws InterruptedException {
        ToolUseBlock tu = toolUse("bash", "tu_001");

        hook.handle(tu);                             // Pre
        Thread.sleep(2);                              // 让耗时不为 0
        hook.handle(tu, "ok: hello");                 // Post

        ToolMetric metric = hook.getMetric("bash");
        assertNotNull(metric);
        assertEquals(1, metric.getCallCount());
        assertEquals(0, metric.getFailureCount());
        assertTrue(metric.avgLatencyMs() >= 1.0,
                "avgMs 应至少 1ms，实际：" + metric.avgLatencyMs());
    }

    @Test
    @DisplayName("失败判定：output 以 'Error' 开头 → failure++")
    void detects_error_output() {
        ToolUseBlock tu = toolUse("bash", "tu_001");
        hook.handle(tu);
        hook.handle(tu, "Error: file not found");

        ToolMetric metric = hook.getMetric("bash");
        assertEquals(1, metric.getCallCount());
        assertEquals(1, metric.getFailureCount());
        assertEquals(1.0, metric.failureRate(), 0.001);
    }

    @Test
    @DisplayName("失败判定：output 以 'Permission denied' 开头 → failure++")
    void detects_permission_denied() {
        ToolUseBlock tu = toolUse("bash", "tu_001");
        hook.handle(tu);
        hook.handle(tu, "Permission denied: User denied");

        ToolMetric metric = hook.getMetric("bash");
        assertEquals(1, metric.getFailureCount());
    }

    @Test
    @DisplayName("成功 + 失败混合：失败率正确")
    void mixed_success_and_failure() {
        // 3 次成功
        for (int i = 0; i < 3; i++) {
            ToolUseBlock tu = toolUse("bash", "tu_ok_" + i);
            hook.handle(tu);
            hook.handle(tu, "ok");
        }
        // 1 次失败
        ToolUseBlock failTu = toolUse("bash", "tu_fail");
        hook.handle(failTu);
        hook.handle(failTu, "Error: oops");

        ToolMetric metric = hook.getMetric("bash");
        assertEquals(4, metric.getCallCount());
        assertEquals(1, metric.getFailureCount());
        assertEquals(0.25, metric.failureRate(), 0.001);
    }

    @Test
    @DisplayName("多工具：指标互不干扰")
    void isolates_metrics_per_tool() {
        ToolUseBlock bash = toolUse("bash", "tu_b");
        ToolUseBlock read = toolUse("read_file", "tu_r");

        hook.handle(bash);
        hook.handle(bash, "ok");

        hook.handle(read);
        hook.handle(read, "Error: not found");

        assertEquals(1, hook.getMetric("bash").getCallCount());
        assertEquals(0, hook.getMetric("bash").getFailureCount());
        assertEquals(1, hook.getMetric("read_file").getCallCount());
        assertEquals(1, hook.getMetric("read_file").getFailureCount());
    }

    @Test
    @DisplayName("Post 找不到 Pre 不崩溃（注册顺序错配的防御）")
    void post_without_pre_does_not_crash() {
        ToolUseBlock tu = toolUse("bash", "tu_orphan");
        // 故意只调 Post 不调 Pre
        assertDoesNotThrow(() -> hook.handle(tu, "ok"));
        // 没记录调用（Pre 都没跑过，不能算）
        assertNull(hook.getMetric("bash"),
                "Post 找不到 Pre 时不应该创建 metric 条目（数据会失真）");
    }

    @Test
    @DisplayName("snapshot 返回所有工具的指标")
    void snapshot_returns_all_metrics() {
        ToolUseBlock bash = toolUse("bash", "tu_b");
        ToolUseBlock read = toolUse("read_file", "tu_r");

        hook.handle(bash);
        hook.handle(bash, "ok");
        hook.handle(read);
        hook.handle(read, "ok");

        Map<String, ToolMetric> snap = hook.snapshot();
        assertEquals(2, snap.size());
        assertTrue(snap.containsKey("bash"));
        assertTrue(snap.containsKey("read_file"));
    }

    @Test
    @DisplayName("summary 输出格式")
    void summary_format() {
        ToolUseBlock tu = toolUse("bash", "tu_001");
        hook.handle(tu);
        hook.handle(tu, "ok");

        String s = hook.summary();
        assertTrue(s.contains("bash"));
        assertTrue(s.contains("calls=1"));
    }

    @Test
    @DisplayName("空 hook → summary 友好提示")
    void empty_summary() {
        assertEquals("(no tool calls)", hook.summary());
    }

    @Test
    @DisplayName("Pre/Post 都返回 empty Optional（不阻止 loop）")
    void never_blocks() {
        ToolUseBlock tu = toolUse("bash", "tu_001");
        assertTrue(hook.handle(tu).isEmpty(), "Pre 不阻止");
        assertTrue(hook.handle(tu, "ok").isEmpty(), "Post 不阻止");
    }

    @Test
    @DisplayName("reset 清空所有状态")
    void reset_clears_state() {
        ToolUseBlock tu = toolUse("bash", "tu_001");
        hook.handle(tu);
        hook.handle(tu, "ok");
        assertNotNull(hook.getMetric("bash"));

        hook.reset();
        assertNull(hook.getMetric("bash"));
        assertEquals("(no tool calls)", hook.summary());
    }
}
