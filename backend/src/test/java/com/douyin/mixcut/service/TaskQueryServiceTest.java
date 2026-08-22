package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.MediaTask;
import com.douyin.mixcut.repository.Repositories.*;
import org.junit.jupiter.api.Test;

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
    }

    private MediaTask mediaTask(String key) {
        MediaTask task = new MediaTask(); task.setTaskKey(key); task.setKind("image"); task.setStatus("done"); task.setProgress(100); task.setMessage("done"); return task;
    }
}
