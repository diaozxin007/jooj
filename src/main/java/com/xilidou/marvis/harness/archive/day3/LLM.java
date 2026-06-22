package com.xilidou.marvis.harness.archive.day3;

import com.xilidou.marvis.harness.archive.day3.Decision;
import com.xilidou.marvis.harness.archive.day3.Message;

import java.util.List;

public interface LLM {
    Decision thinkAndAct(List<Message> messages);
}
