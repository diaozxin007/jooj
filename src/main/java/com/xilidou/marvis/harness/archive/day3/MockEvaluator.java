package com.xilidou.marvis.harness.archive.day3;

import com.xilidou.marvis.harness.archive.day3.ValidationResult;
import com.xilidou.marvis.harness.archive.day3.Evaluator;

public class MockEvaluator implements Evaluator {
    @Override
    public ValidationResult check(String action, Object result) {

        if ("submit_answer".equals(action)) {
            if (result.toString().toLowerCase().contains("rain")) {
                return new ValidationResult(false, "Forecast seems wrong, re-check.");
            }
            return new ValidationResult(true, "OK");
        }
        return new ValidationResult(true, "Pass");
    }
}
