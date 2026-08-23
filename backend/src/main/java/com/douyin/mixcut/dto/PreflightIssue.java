package com.douyin.mixcut.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A safe, user-facing reason why a dry-run is blocked or needs attention. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreflightIssue {
    private String code;
    private String category;
    private String severity;
    private String message;
    private String action;

    public static PreflightIssue blocker(String code, String category, String message, String action) {
        return new PreflightIssue(code, category, "blocker", message, action);
    }

    public static PreflightIssue warning(String code, String category, String message, String action) {
        return new PreflightIssue(code, category, "warning", message, action);
    }
}
