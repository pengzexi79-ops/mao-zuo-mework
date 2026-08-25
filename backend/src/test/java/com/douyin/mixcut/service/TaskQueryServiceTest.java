package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.MediaGenerationTask;
import com.douyin.mixcut.domain.MediaTask;
import com.douyin.mixcut.repository.Repositories.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskQueryServiceTest {
    @Test
    void mapsSourceAndLimitsNormalizedTasks() {
        MediaTaskRepo media = mock(MediaTaskRepo.class);
        when(media.findTop50ByOrderByIdDesc()).thenReturn(List.of(mediaTask("m-1")));
        MediaGenerationTaskRepo ai = mock(MediaGenerationTaskRepo.class);
        CrawlJobRepo crawl = mock(CrawlJobRepo.class);
        PreparationTaskRepo prep = mock(PreparationTaskRepo.class);
        JobRepo jobs = mock(JobRepo.class);
        TaskQueryService service = new TaskQueryService(media, ai, crawl, prep, jobs);
        var result = service.list(1, "media", null);
        assertEquals(1, result.size());
        assertEquals("media", result.get(0).getSource());
        assertEquals("m-1", result.get(0).getId());
        assertEquals("finished", result.get(0).getPhase());
        assertEquals("MEDIA_EXECUTION_FAILED", result.get(0).getErrorCode());
        assertEquals(2, result.get(0).getRetryCount());
    }

    @Test
    void mapsAiGenerationDiagnosticsWithoutAdvertisingUnsupportedRetry() {
        MediaGenerationTaskRepo ai = mock(MediaGenerationTaskRepo.class);
        MediaGenerationTask task = new MediaGenerationTask();
        task.setTaskKey("ai-1");
        task.setKind("ai-video");
        task.setStatus("failed_terminal");
        task.setPhase("polling");
        task.setProgress(50);
        task.setErrorCode("RATE_LIMITED");
        task.setAttemptCount(1);
        task.setLastActivityAt(LocalDateTime.now());
        when(ai.findTop50ByOrderByIdDesc()).thenReturn(List.of(task));
        TaskQueryService service = new TaskQueryService(mock(MediaTaskRepo.class), ai, mock(CrawlJobRepo.class), mock(PreparationTaskRepo.class), mock(JobRepo.class));

        var result = service.list(10, "ai-generation", null).get(0);

        assertEquals("polling", result.getPhase());
        assertEquals("RATE_LIMITED", result.getErrorCode());
        assertEquals(1, result.getRetryCount());
        assertEquals(false, result.isCanRetry());
    }

    @Test
    void mediaTaskAtRetryLimitCannotBeRetried() {
        MediaTaskRepo media = mock(MediaTaskRepo.class);
        MediaTask task = mediaTask("m-limit");
        task.setStatus("failed");
        task.setRetryCount(3);
        when(media.findTop50ByOrderByIdDesc()).thenReturn(List.of(task));
        TaskQueryService service = new TaskQueryService(media, mock(MediaGenerationTaskRepo.class), mock(CrawlJobRepo.class), mock(PreparationTaskRepo.class), mock(JobRepo.class));

        assertEquals(false, service.list(10, "media", null).get(0).isCanRetry());
    }

    private MediaTask mediaTask(String key) {
        MediaTask task = new MediaTask(); task.setTaskKey(key); task.setKind("image"); task.setStatus("done"); task.setPhase("finished"); task.setProgress(100); task.setMessage("done"); task.setErrorCode("MEDIA_EXECUTION_FAILED"); task.setRetryCount(2); task.setLastActivityAt(LocalDateTime.now()); return task;
    }
}
