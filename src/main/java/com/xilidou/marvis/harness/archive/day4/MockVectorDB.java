package com.xilidou.marvis.harness.archive.day4;

import com.xilidou.marvis.harness.archive.day4.MemoryService;

import java.util.HashMap;
import java.util.Map;

public class MockVectorDB implements MemoryService {

    private final Map<String, String> knowledgeBase = new HashMap<>();

    public MockVectorDB() {
        // 模拟预存的知识
        knowledgeBase.put("project_alpha", "Project Alpha is currently blocked due to API rate limits. Expected fix date: May 25th.");
        knowledgeBase.put("project_beta", "Project Beta launched successfully on May 10th. Current conversion rate is 5%.");
        knowledgeBase.put("hr_policy", "Remote work policy allows 2 days per week for senior engineers.");
    }

    @Override
    public String search(String query) {
        System.out.println("🔍 [RAG] 检索关键词: " + query);
// 模拟语义匹配：简单的关键词包含检查
// 实际场景中这里是 向量相似度计算 (Cosine Similarity)
        for (String key : knowledgeBase.keySet()) {
            if (query.toLowerCase().contains(key) || key.contains(query.toLowerCase())) {
                return "Context Found: " + knowledgeBase.get(key);
            }
        }
        return "Context Empty: No relevant information found.";
    }
}
