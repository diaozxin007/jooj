package com.xilidou.jooj.web;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;

/**
 * HealthController —— 轻量健康探活端点(s25 G9.1)。
 *
 * <h3>为什么单开一个 controller</h3>
 *
 * <p>与 {@link SidebarController#status} 区分:
 * <ul>
 *   <li>{@code /api/status} 面向前端 UI, 返 model/tool/skill/cron/memory 计数
 *       —— 需要走多个 registry, cronService 未 ready 时会 log warn</li>
 *   <li>{@code /api/health} 面向<strong>客户端探活</strong>(jooj-tui / 未来的
 *       monitoring), 只需要 JVM 存活的最简断言 —— 无外部依赖调用, 无日志噪音,
 *       响应体固定 shape, 快</li>
 * </ul>
 *
 * <p>与 {@code /actuator/health} 区分:actuator 输出含 disk/db/... 各种 details,
 * 不适合作为客户端简单探活的返回契约 (breaking-change 风险)。本端点契约由 jooj
 * 团队自己 own, s25 §二清单里明确写入。
 *
 * <h3>客户端使用</h3>
 *
 * <p>jooj-tui Go client 每 15s poll 一次 {@code /api/health};连续 3 次失败(45s)
 * 视为断开触发 reconnect。详见 s25 §六 G9.1。
 *
 * <h3>响应契约</h3>
 *
 * <pre>
 *   200 OK
 *   Content-Type: application/json
 *
 *   {
 *     "status": "ok",
 *     "uptimeSeconds": 12345
 *   }
 * </pre>
 *
 * <p>{@code status} 目前只有 "ok" 一种(JVM 起来了就算 ok)。未来若加 degraded
 * / down 状态,客户端应视非 "ok" 为不健康 (定义: "ok" == healthy, 其他 == unhealthy)。
 *
 * <p>{@code uptimeSeconds} 是 JVM uptime,便于诊断"backend 是否被重启过"。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        return new HealthResponse("ok", uptimeMs / 1000L);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HealthResponse {
        private String status;         // "ok" | (future: "degraded" | "down")
        private long uptimeSeconds;    // JVM uptime
    }
}
