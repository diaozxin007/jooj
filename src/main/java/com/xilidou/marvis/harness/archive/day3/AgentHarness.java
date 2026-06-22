package com.xilidou.marvis.harness.archive.day3;

import com.xilidou.marvis.harness.archive.day3.ToolRegistry;
import com.xilidou.marvis.harness.archive.day3.*;
import com.xilidou.marvis.harness.archive.day3.Evaluator;
import com.xilidou.marvis.harness.archive.day3.LLM;

import java.util.List;

public class AgentHarness {

    private final LLM llm;

    private final Evaluator evaluator;

    private final ToolRegistry toolRegistry;

    private final ContextManager contextManager;


    private String state = "IDLE"; // IDLE, RUNNING, FINISHED


    public AgentHarness(LLM llm, Evaluator evaluator, ToolRegistry toolRegistry, int contextWindow) {
        this.llm = llm;
        this.evaluator = evaluator;
        this.toolRegistry = toolRegistry;
        this.contextManager = new ContextManager(contextWindow);
    }

    public void  runTurn(){
        List<Message> history = contextManager.getContext();

        Decision decision = llm.thinkAndAct(history);

        Object result = null;
        boolean isFinal =false;

        if("submit_answer".equals(decision.getAction())){
            result = decision.getArgs();
            isFinal = true;
        }else if(decision.getAction() != null){
            result = toolRegistry.execute(decision.getAction(),decision.getArgs());
        }

        if(isFinal){
            ValidationResult res = evaluator.check(decision.getAction(), result);
            if(!res.isValid()){
                contextManager.addMessage("system", "Evaluator Feedback: " + res.getFeedback());
                System.out.println("❌ [Harness] Evaluator 拦截: " + res.getFeedback());
                return;
            }
            state = "FINISHED";
            System.out.println("✅ [Harness] 任务完成: " + result);
        }else {
            // 普通工具调用，记录上下文
            contextManager.addMessage("assistant", decision.getThought());
            if (result != null) contextManager.addMessage("tool", result.toString());
            state = "RUNNING";
        }



    }

    public String getState() {
        return state;
    }


}
