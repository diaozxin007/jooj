package com.xilidou.marvis.harness.archive.day4;

import com.xilidou.marvis.harness.archive.day3.Decision;
import com.xilidou.marvis.harness.archive.day4.MemoryService;
import com.xilidou.marvis.harness.archive.day4.MockVectorDB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RAGAgentHarness {
    private final MemoryService memoryService;
    private String state = "RUNNING";
    private int turnCount = 0;
    private final int maxTurns = 5;

    // 模拟 LLM 的决策逻辑 (在实际中由 API 驱动)
// 这里为了演示，我们用硬编码模拟 LLM 看到不同上下文时的反应
    private final List<String> conversationHistory = new ArrayList<>();

    public RAGAgentHarness(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public void processUserInput(String userInput) {
        System.out.println("\n👤 [User]: " + userInput);
        conversationHistory.add(userInput);
        turnCount = 0;
        state = "RUNNING";
        runLoop();
    }

    private void runLoop() {
        while ("RUNNING".equals(state) && turnCount < maxTurns) {
            turnCount++;

// 1. Agent 思考 (Mock)
            Decision decision = mockLLMDecision(conversationHistory);
            System.out.println("🤖 [Agent Thought]: " + decision.getThought());

// 2. 执行动作
            if ("SEARCH_MEMORY".equals(decision.getAction())) {
                String query = (String) decision.getArgs().get("query");
                String context = memoryService.search(query);

// 3. 观察结果 (注入上下文)
                System.out.println("📦 [RAG Result]: " + context);
                conversationHistory.add("System Context: " + context);

// 循环继续，Agent 将基于新 Context 再次思考
            } else if ("SUBMIT_ANSWER".equals(decision.getAction())) {
                String answer = (String) decision.getArgs().get("answer");
                System.out.println("✅ [Agent Final Answer]: " + answer);
                state = "FINISHED";
                conversationHistory.add("Agent: " + answer);
            } else {
                System.out.println("⚠️ [Agent]: Idle/Unknown action.");
                state = "FINISHED"; // Fallback
            }
        }
    }

    // 模拟 LLM 大脑：根据当前对话历史决定下一步
    private Decision mockLLMDecision(List<String> history) {
        String lastMsg = history.get(history.size() - 1);

// 场景 A: 刚收到用户问题，且问题包含 "Alpha"
        if (lastMsg.contains("Alpha")) {
// LLM 发现自己不知道，决定搜索
            if (!history.stream().anyMatch(s -> s.contains("Context Found"))) {
                return new Decision("SEARCH_MEMORY", Map.of("query", "project_alpha"), "User asks about Alpha. I need to check the internal knowledge base.");
            }
// LLM 收到了 Context，准备回答
            else if (history.stream().anyMatch(s -> s.contains("blocked"))) {
                return new Decision("SUBMIT_ANSWER", Map.of("answer", "Project Alpha is currently blocked due to API limits. Fix expected by May 25th."), "Found the info. Synthesizing answer.");
            }
        }

// 场景 B: 默认兜底
        return new Decision("SUBMIT_ANSWER", Map.of("answer", "I don't understand."), "Default response");
    }

    public static void main(String[] args) {
        System.out.println("🚀 Day 4: Agent RAG Demo\n" + "=".repeat(40));

// 初始化 RAG 数据库
        MockVectorDB db = new MockVectorDB();

// 初始化 Agent
        RAGAgentHarness agent = new RAGAgentHarness(db);

// 用户提问
        agent.processUserInput("What is the status of Project Alpha?");

        System.out.println("\n" + "=".repeat(40));

// 用户提问 2 (测试未命中)
        agent.processUserInput("Tell me about Project Gamma.");
    }

}
