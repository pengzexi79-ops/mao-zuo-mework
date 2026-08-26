package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.CrawlJob;
import com.douyin.mixcut.domain.CrawlTask;
import com.douyin.mixcut.domain.JobStatus;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.repository.Repositories.CrawlJobRepo;
import com.douyin.mixcut.repository.Repositories.CrawlTaskRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CrawlJobServiceTest {
    @Mock private CrawlJobRepo jobRepo;
    @Mock private CrawlTaskRepo taskRepo;
    @Mock private CrawlerGateway crawler;
    @Mock private MaterialService materialService;

    @Test
    void labelsQualityRejectedDownloadsWithoutClaimingTheyWereImported() {
        CrawlJob job = new CrawlJob();
        job.setId(1L);
        job.setMode("video");
        job.setRole("body");
        job.setStatus(JobStatus.pending.name());
        job.setTotal(1);
        CrawlTask task = new CrawlTask();
        task.setJobId(1L);
        task.setIdx(0);
        task.setUrl("https://media.example.invalid/placeholder.mp4");
        task.setStatus(JobStatus.pending.name());
        CrawlerGateway.FetchResult download = new CrawlerGateway.FetchResult();
        download.setOk(true);
        download.setFilePath("C:/test/placeholder.mp4");
        download.setVia("http");
        Material material = new Material();
        material.setId(3L);
        material.setName("placeholder.mp4");
        material.setStatus(Material.Status.failed);
        material.setTags("低质:画面冻结/几乎静止");

        when(jobRepo.findById(1L)).thenReturn(Optional.of(job));
        when(taskRepo.findByJobIdOrderByIdxAsc(1L)).thenReturn(List.of(task));
        when(taskRepo.countByJobIdAndStatus(1L, JobStatus.done.name())).thenReturn(1L);
        when(taskRepo.countByJobIdAndStatus(1L, JobStatus.failed.name())).thenReturn(0L);
        when(crawler.fetchVideo(anyString())).thenReturn(download);
        when(materialService.registerDownloaded(anyString(), anyString(), any())).thenReturn(material);
        Executor inline = Runnable::run;
        CrawlJobService service = new CrawlJobService(jobRepo, taskRepo, crawler, materialService, new AppProps(), inline);

        service.run(1L);

        assertEquals(JobStatus.done.name(), task.getStatus());
        assertTrue(task.getMessage().startsWith("已下载但未通过质量准入："));
        assertFalseContains(task.getMessage(), "已入库");
    }

    @Test
    void persistsStructuredCrawlerFailureDiagnostics() {
        CrawlJob job = new CrawlJob();
        job.setId(2L);
        job.setMode("video");
        job.setRole("body");
        job.setStatus(JobStatus.pending.name());
        job.setTotal(1);
        CrawlTask task = new CrawlTask();
        task.setJobId(2L);
        task.setIdx(0);
        task.setUrl("https://media.example.invalid/expired.mp4");
        task.setStatus(JobStatus.pending.name());
        CrawlerGateway.FetchResult failure = new CrawlerGateway.FetchResult();
        failure.setOk(false);
        failure.setMessage("来源未找到该媒体（HTTP 404），链接可能已失效。");
        failure.setErrorCode("HTTP_NOT_FOUND");
        failure.setSource("archive");
        failure.setHttpStatus(404);

        when(jobRepo.findById(2L)).thenReturn(Optional.of(job));
        when(taskRepo.findByJobIdOrderByIdxAsc(2L)).thenReturn(List.of(task));
        when(taskRepo.countByJobIdAndStatus(2L, JobStatus.done.name())).thenReturn(0L);
        when(taskRepo.countByJobIdAndStatus(2L, JobStatus.failed.name())).thenReturn(1L);
        when(crawler.fetchVideo(anyString())).thenReturn(failure);
        CrawlJobService service = new CrawlJobService(jobRepo, taskRepo, crawler, materialService, new AppProps(), Runnable::run);

        service.run(2L);

        assertEquals(JobStatus.failed.name(), task.getStatus());
        assertEquals("HTTP_NOT_FOUND", task.getErrorCode());
        assertEquals("archive", task.getSource());
        assertEquals(404, task.getHttpStatus());
        assertTrue(task.getMessage().contains("HTTP 404"));
    }

    @Test
    void remoteTaskStoresCanonicalPageUrlInsteadOfSignedDownloadUrl() {
        CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
        item.setSource("wikimedia");
        item.setType("video");
        item.setTitle("flower.mp4");
        item.setPageUrl("https://commons.wikimedia.org/wiki/File:flower.mp4?tracking=1");
        item.setDownloadUrl("https://upload.wikimedia.org/flower.mp4?token=SECRET&sig=SIGNED");
        item.setLicense("CC BY 4.0");
        CrawlJob saved = new CrawlJob();
        saved.setId(7L);
        when(jobRepo.save(any(CrawlJob.class))).thenReturn(saved);
        when(taskRepo.save(any(CrawlTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CrawlJobService service = new CrawlJobService(jobRepo, taskRepo, crawler, materialService, new AppProps(), Runnable::run);
        when(crawler.validateRemoteItem(any(CrawlerGateway.RemoteItem.class), anyString())).thenReturn(item);
        service.submitVideoItems(List.of(item), "body");
        ArgumentCaptor<CrawlJob> jobs = ArgumentCaptor.forClass(CrawlJob.class);
        verify(jobRepo, org.mockito.Mockito.atLeastOnce()).save(jobs.capture());
        String params = jobs.getAllValues().get(0).getParams();
        assertTrue(params.contains("commons.wikimedia.org/wiki/File:flower.mp4"));
        assertFalseContains(params, "token=SECRET");
        assertFalseContains(params, "upload.wikimedia.org/flower.mp4");
    }

    private void assertFalseContains(String text, String fragment) {
        if (text != null && text.contains(fragment)) {
            throw new AssertionError("unexpected fragment: " + fragment);
        }
    }
}
