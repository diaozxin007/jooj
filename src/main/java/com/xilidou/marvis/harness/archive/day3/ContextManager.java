package com.xilidou.marvis.harness.archive.day3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContextManager {

    private List<Message> messages = new ArrayList<>();
    private int maxHistory;

    public ContextManager(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    public void addMessage(String role, String content) {
        messages.add(new Message(role, content));
// 截断策略：保留 System (index 0) 和最后 N 条
        if (messages.size() > maxHistory + 1) {
            Message system = messages.get(0);
            List<Message> recent = messages.subList(messages.size() - maxHistory, messages.size());

            List<Message> newHistory = new ArrayList<>();
            newHistory.add(system);
            newHistory.addAll(recent);
            messages = newHistory;
        }
    }

    public List<Message> getContext() {
        return Collections.unmodifiableList(messages);
    }

}
