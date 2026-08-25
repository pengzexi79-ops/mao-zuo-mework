package com.douyin.mixcut.dto;

import com.douyin.mixcut.service.MixPlanner;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Unified dry-run contract consumed by Studio and future batch admission checks. */
@Data
public class PreflightResult {
    public static final String READY = "ready";
    public static final String BLOCKED = "blocked";
    public static final String WARNING = "warning";
    public static final String NEEDS_USER_ACTION = "needs_user_action";

    private String status;
    private double plannedSec;
    private double minSec;
    private double maxSec;
    private double targetSec;
    private double usableVisualSec;
    private double excludedVisualSec;
    private int visualCount;
    private int totalVisualCount;
    private boolean planUsable;
    private boolean internallyUnique;
    private boolean requiresExternalAudio;
    private String audioCoverageStatus = "not_required";
    private AudioPreflightResult audio;
    private List<PreflightIssue> blockers = new ArrayList<>();
    private List<PreflightIssue> warnings = new ArrayList<>();
    private List<String> actions = new ArrayList<>();
    private MixPlanner.Plan plan;
    private AdmissionSnapshot admission;

    public boolean isReady() {
        return READY.equals(status) || WARNING.equals(status);
    }
}
