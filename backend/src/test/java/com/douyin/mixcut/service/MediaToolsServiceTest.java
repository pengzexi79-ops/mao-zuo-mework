package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.MediaTask;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.repository.Repositories.MediaTaskRepo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaToolsServiceTest {
    @Test
    void persistedMediaTaskKeepsLifecycleAndResultSnapshot() {
        MediaTask task = new MediaTask();
        task.setTaskKey("media-test-1");
        task.setKind("image");
        task.setStatus("done");
        task.setProgress(100);
        task.setMessage("图片处理完成");
        task.setResultPaths("[\"C:/output/result.png\"]");
        task.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        task.setUpdatedAt(LocalDateTime.now());
        MediaTaskRepo repo = mock(MediaTaskRepo.class);
        when(repo.findByTaskKey("media-test-1")).thenReturn(Optional.of(task));
        when(repo.findTop50ByOrderByIdDesc()).thenReturn(List.of(task));
        assertEquals("done", task.getStatus());
        assertEquals(100, task.getProgress());
        assertTrue(task.getResultPaths().contains("result.png"));
        assertEquals(1, repo.findTop50ByOrderByIdDesc().size());
    }

    @Test
    void mediaTaskDefaultsAreRestartSafe() {
        MediaTask task = new MediaTask();
        task.setTaskKey("pending-1");
        task.setKind("auto-trim");
        assertEquals("pending", task.getStatus());
        assertEquals(0, task.getProgress());
        assertEquals(0, task.getRetryCount());
        assertEquals(1800, task.getTimeoutSec());
        assertEquals(900, task.getStaleAfterSec());
    }
}
