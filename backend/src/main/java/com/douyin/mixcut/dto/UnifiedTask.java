package com.douyin.mixcut.dto;

import lombok.Data;

/** Read-only normalized task view across local media, AI, crawl, preparation and render. */
@Data
public class UnifiedTask {
    private String id;
    private String source;
    private String type;
    private String rawStatus;
    private String phase;
    private int progress;
    private String label;
    private String message;
    private String createdAt;
    private String updatedAt;
    private String heartbeatAt;
    private Integer timeoutSec;
    private Integer staleAfterSec;
    private String recoveryState;
    private String recoveryReason;
    private String errorCode;
    private Integer retryCount;
    private boolean canCancel;
    private boolean canRetry;
}
