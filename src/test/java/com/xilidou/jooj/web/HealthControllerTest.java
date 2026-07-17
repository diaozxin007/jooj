package com.xilidou.jooj.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.JoojTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HealthController IT (s25 G9.1) —— 验证 GET /api/health 返回契约。
 *
 * <p>用途:jooj-tui Go client 每 15s poll 一次;契约需要稳定不能随便改。
 * 未来若加 "degraded" 状态或改 shape,先改这里的测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JoojTestConfig.class)
@DisplayName("HealthController /api/health")
class HealthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("GET /api/health 返 200 + status=ok + uptimeSeconds>=0")
    void health_returns_ok() throws Exception {
        String body = mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.uptimeSeconds").isNumber())
                .andReturn().getResponse().getContentAsString();

        // 精确断言 uptime >=0 (可能为 0 因为 JVM 刚启动毫秒级)
        JsonNode node = json.readTree(body);
        long uptime = node.get("uptimeSeconds").asLong();
        assertThat(uptime).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("GET /api/health 只有 2 个字段, 未来加字段应显式过测试")
    void health_shape_is_stable() throws Exception {
        String body = mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = json.readTree(body);
        // 2 字段: status + uptimeSeconds. 增加字段时这个断言 fail, 提醒更新契约文档
        assertThat(node.size()).isEqualTo(2);
        assertThat(node.has("status")).isTrue();
        assertThat(node.has("uptimeSeconds")).isTrue();
    }
}
