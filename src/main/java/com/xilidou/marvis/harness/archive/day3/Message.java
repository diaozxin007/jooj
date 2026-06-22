package com.xilidou.marvis.harness.archive.day3;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class Message {

    private String role;

    private String content;

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
