package com.xilidou.jooj.agent.control;

/**
 * 自由文本答复,预留给 clarify / choose-option 场景。
 * D-10-B 阶段暂不用,但先把类型定义好,让 sealed 契约完备。
 */
public record TextAnswer(String text) implements Answer {}
