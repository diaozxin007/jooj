package com.xilidou.marvis.harness.archive.day3;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class ValidationResult {
    private boolean isValid;
    private String feedback;
}
