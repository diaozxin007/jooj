package com.xilidou.jooj.agent.control;

/** 用户"允许"型答复。permission ASK 场景返 ALLOW,tool 可执行。 */
public record AllowAnswer() implements Answer {
    public static final AllowAnswer INSTANCE = new AllowAnswer();
}
