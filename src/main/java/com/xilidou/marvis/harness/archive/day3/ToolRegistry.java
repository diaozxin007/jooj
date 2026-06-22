package com.xilidou.marvis.harness.archive.day3;

import com.xilidou.marvis.harness.archive.day3.Tool;

import java.util.HashMap;
import java.util.Map;

public class ToolRegistry {

    private Map<String, Tool> tools = new HashMap<>();

    public void register(String name, Tool tool) {
        tools.put(name, tool);
    }

    public String execute(String name, Map<String, Object> args) {
        if (!tools.containsKey(name)) {
            return "Error: Tool '" + name + "' not found.";
        }
        try {
            return tools.get(name).execute(args);
        } catch (Exception e) {
            return "Execution Error: " + e.getMessage();
        }
    }
}
