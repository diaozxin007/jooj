package com.xilidou.jooj.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * s22 D-10:{@link AgentControl} 默认实现 —— 单进程 JVM + ConcurrentHashMap。
 *
 * <p><b>历史</b>:D-8 最初叫 {@code InterruptRegistry},只有 signal 部分。D-10-A 把它改名 +
 * 上到接口后,准备在 D-10-B 加 ask 部分(pending question / CompletableFuture 唤醒)。
 *
 * <p><b>为什么单 Bean 就够</b>:jooj 是单进程 JVM(pidfile guard),不涉及跨进程可见性;
 * REST 线程 write flag,agentLoop 线程 read/consume,ConcurrentHashMap 足够。
 * 未来若需要多进程,该抽象换 Redis / ZK 实现即可,接口不变。
 */
@Component
@Slf4j
public class DefaultAgentControl implements AgentControl {

    /** Set 语义,ConcurrentHashMap.newKeySet 拿并发 add/remove. */
    private final Set<String> pendingInterrupts = ConcurrentHashMap.newKeySet();

    @Override
    public boolean requestInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        boolean added = pendingInterrupts.add(sessionId);
        if (added) {
            log.info("[AgentControl] interrupt requested sid={}", sessionId);
        } else {
            log.debug("[AgentControl] duplicate interrupt request sid={} (already pending)", sessionId);
        }
        return added;
    }

    @Override
    public boolean consumeInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        boolean removed = pendingInterrupts.remove(sessionId);
        if (removed) {
            log.info("[AgentControl] interrupt consumed sid={} — loop will stop", sessionId);
        }
        return removed;
    }

    @Override
    public boolean isInterruptRequested(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return pendingInterrupts.contains(sessionId);
    }

    @Override
    public void clearInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        pendingInterrupts.remove(sessionId);
    }

    /** 测试可见:当前挂起请求数。 */
    int pendingInterruptCount() {
        return pendingInterrupts.size();
    }
}
