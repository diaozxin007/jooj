package com.xilidou.jooj.slashcmd.impl;

import com.xilidou.jooj.agent.AgentLoopHarness;
import com.xilidou.jooj.slashcmd.SlashCommand;
import org.springframework.stereotype.Component;

/** /clear —— 清空当前 session 的对话历史。复用 AgentLoopHarness.clearHistory。 */
@Component
public class ClearCommand implements SlashCommand {

    private final AgentLoopHarness harness;

    public ClearCommand(AgentLoopHarness harness) {
        this.harness = harness;
    }

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clear the current session's conversation history.";
    }

    @Override
    public String execute(String args, String sessionId) {
        harness.clearHistory(sessionId);
        return "✓ Conversation history cleared (session: " + sessionId + ")";
    }
}
