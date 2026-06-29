package com.xilidou.jooj.slashcmd.impl;

import com.xilidou.jooj.session.Session;
import com.xilidou.jooj.session.SessionService;
import com.xilidou.jooj.slashcmd.SlashCommand;
import org.springframework.stereotype.Component;

import java.util.List;

/** /sessions —— 列出所有 session(id + title + 消息数)。 */
@Component
public class SessionsCommand implements SlashCommand {

    private final SessionService sessionService;

    public SessionsCommand(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String name() {
        return "sessions";
    }

    @Override
    public String description() {
        return "List all sessions with their id and title.";
    }

    @Override
    public String execute(String args, String sessionId) {
        List<Session> all = sessionService.list();
        if (all.isEmpty()) return "(no sessions)";
        StringBuilder sb = new StringBuilder();
        sb.append("Sessions (").append(all.size()).append("):\n");
        for (Session s : all) {
            String marker = s.id().equals(sessionId) ? " ← current" : "";
            sb.append("  - ").append(s.id())
              .append("  ").append(s.title() == null ? "(untitled)" : s.title())
              .append(marker).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
