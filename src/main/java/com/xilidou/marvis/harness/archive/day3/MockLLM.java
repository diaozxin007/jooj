package com.xilidou.marvis.harness.archive.day3;

import com.xilidou.marvis.harness.archive.day3.Decision;
import com.xilidou.marvis.harness.archive.day3.Message;
import com.xilidou.marvis.harness.archive.day3.LLM;

import java.util.List;
import java.util.Map;

public class MockLLM implements LLM {
    private int step = 0;
    @Override
    public Decision thinkAndAct(List<Message> messages) {
        step++;
        System.out.println("🤖 [LLM Step " + step + "] 思考中...");

        if (step == 1) {
            return new Decision("get_weather", Map.of("city", "Beijing"), "Check weather.");
        }
        if (step == 2) {
            return new Decision("submit_answer", Map.of("report", "Beijing: Sunny (25C)"), "Done.");
        }
        return new Decision("wait", null, "Idle");
    }
}
