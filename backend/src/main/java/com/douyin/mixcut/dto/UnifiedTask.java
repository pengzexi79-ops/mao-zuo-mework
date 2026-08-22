package com.douyin.mixcut.dto;

import lombok.Data;

/** Read-only normalized task view across local media, AI, crawl, preparation and render. */
@Data
public class UnifiedTask {
    private String id;
    private String source;
    private String type;
    private String rawStatus;
    private int progress;
    private String label;
    private String message;
    private String createdAt;
    private String updatedAt;
    private boolean canCancel;
    private boolean canRetry;
}
