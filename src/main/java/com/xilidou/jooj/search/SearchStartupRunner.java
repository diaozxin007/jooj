package com.xilidou.jooj.search;

import com.xilidou.jooj.llm.domain.LlmContent;
import com.xilidou.jooj.llm.domain.LlmMessage;
import com.xilidou.jooj.llm.domain.LlmText;
import com.xilidou.jooj.llm.domain.LlmToolResult;
import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.session.SessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动期一致性检查 —— 监听 {@link ApplicationReadyEvent},按 startupCheck 模式分支。
 *
 * <ul>
 *   <li>{@code none} — 不查</li>
 *   <li>{@code light}(默认)— 在 SearchStore 构造期已经做过 schema_meta.version 校验,
 *       这里只 log 一行 info 表示启动健康</li>
 *   <li>{@code strict} — 遍历所有 session 对 {@code countSession(sid)} vs JSON 中可索引 message 数</li>
 * </ul>
 *
 * <p><b>不在启动期触发全量 rebuild</b>:jooj REPL 启动期不该卡。strict 模式发现不一致也只 log warn,
 * 用户必要时调 {@link SearchService#rebuildAll}。
 */
@Component
@Slf4j
public class SearchStartupRunner implements ApplicationListener<ApplicationReadyEvent> {

    private final SearchService service;
    private final SearchConfig config;
    private final SessionService sessionService;
    private final SessionStore sessionStore;

    public SearchStartupRunner(SearchService service,
                               SearchConfig config,
                               SessionService sessionService,
                               SessionStore sessionStore) {
        this.service = service;
        this.config = config;
        this.sessionService = sessionService;
        this.sessionStore = sessionStore;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String mode = config.startupCheck();
        switch (mode) {
            case "none" -> log.info("[Search] startup check: none (mode=none)");
            case "strict" -> runStrict();
            default -> log.info("[Search] startup check: light, fts rows={}", service.countAll());
        }
    }

    private void runStrict() {
        List<Session> sessions = sessionService.list();
        int mismatches = 0;
        for (Session s : sessions) {
            String sid = s.id();
            int ftsCount = service.countSession(sid);
            int jsonIndexable = countIndexableInJson(sid);
            if (ftsCount != jsonIndexable) {
                log.warn("[Search] strict mismatch session={}: fts={} json_indexable={}",
                        sid, ftsCount, jsonIndexable);
                mismatches++;
            }
        }
        if (mismatches > 0) {
            log.warn("[Search] strict check: {} session(s) inconsistent. " +
                    "Call SearchService.rebuildAll(sessionService, sessionStore) to fix.", mismatches);
        } else {
            log.info("[Search] strict check passed for {} session(s)", sessions.size());
        }
    }

    /**
     * 数 JSON 中可索引 message 数 —— 跟 SearchStore.insertHistory 同一套逻辑。
     * 只是用来跟 fts 表行数对照。
     */
    private int countIndexableInJson(String sessionId) {
        try {
            List<LlmMessage> hist = sessionStore.readCanonicalHistory(sessionId);
            int count = 0;
            for (LlmMessage m : hist) {
                if (m == null || m.getContent() == null) continue;
                for (LlmContent c : m.getContent()) {
                    if (c instanceof LlmText t && t.getText() != null && !t.getText().isEmpty()) {
                        count++;
                    } else if (c instanceof LlmToolResult tr
                            && tr.getOutput() != null && !tr.getOutput().isEmpty()) {
                        count++;
                    }
                    // LlmToolCall / LlmThinking / LlmOpaque 不索引
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("[Search] countIndexableInJson({}) failed: {}", sessionId, e.toString());
            return -1;
        }
    }
}
