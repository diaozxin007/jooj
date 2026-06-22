package com.xilidou.marvis.harness.archive.day3;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;


@AllArgsConstructor
@Data
public class Decision {

    private String action;
    private Map<String,Object> args;
    private String thought;

}
