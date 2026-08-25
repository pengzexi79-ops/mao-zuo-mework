package com.douyin.mixcut.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Audio-mode-specific facts attached to a dry-run preflight result. */
@Data
public class AudioPreflightResult {
    private String mode;
    private String status = PreflightResult.READY;
    private String coverageStatus = "not_required";
    private boolean voicePresent;
    private boolean bgmPresent;
    private boolean originalAudioPresent;
    private AudioContract voiceContract;
    private AudioContract bgmContract;
    private List<String> contractCodes = new ArrayList<>();
    private List<PreflightIssue> blockers = new ArrayList<>();
    private List<PreflightIssue> warnings = new ArrayList<>();
    private List<String> actions = new ArrayList<>();
}
