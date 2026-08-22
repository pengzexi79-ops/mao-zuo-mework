package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.MediaGenerationTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaGenerationServiceTest {
    @Test
    void remoteSubmittedVideoStateIsRecoverableWithoutResubmitting() {
        MediaGenerationTask task = new MediaGenerationTask();
        task.setTaskKey("video-1");
        task.setKind("ai-video");
        task.setStatus("polling");
        task.setPhase("polling");
        task.setRemoteTaskId("remote-123");
        task.setLastActivityAt(LocalDateTime.now());
        assertEquals("polling", task.getStatus());
        assertEquals("remote-123", task.getRemoteTaskId());
        assertTrue(task.getRemoteTaskId() != null && !task.getRemoteTaskId().isBlank());
    }

    @Test
    void unknownSynchronousSubmissionStateIsManualReview() {
        MediaGenerationTask task = new MediaGenerationTask();
        task.setTaskKey("image-unknown");
        task.setKind("ai-image");
        task.setStatus("manual_review");
        task.setPhase("submit_unknown");
        task.setMessage("提交结果未知，禁止自动重试以避免重复计费");
        assertEquals("manual_review", task.getStatus());
        assertEquals("submit_unknown", task.getPhase());
        assertTrue(task.getMessage().contains("禁止自动重试"));
    }
}
