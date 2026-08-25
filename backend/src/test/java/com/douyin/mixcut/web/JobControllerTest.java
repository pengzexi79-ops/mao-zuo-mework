package com.douyin.mixcut.web;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Job;
import com.douyin.mixcut.dto.AdmissionSnapshot;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.Repositories.JobOutputRepo;
import com.douyin.mixcut.repository.Repositories.JobRepo;
import com.douyin.mixcut.service.DeliveryRepairService;
import com.douyin.mixcut.service.JobService;
import com.douyin.mixcut.service.MaterialGapService;
import com.douyin.mixcut.service.RenderPreparationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class JobControllerTest {
    @Mock private JobService jobService;
    @Mock private RenderPreparationService preparationService;
    @Mock private JobRepo jobRepo;
    @Mock private JobOutputRepo outputRepo;
    @Mock private DeliveryRepairService deliveryRepairService;
    @Mock private FfmpegTool ffmpeg;

    private JobController controller;

    @BeforeEach
    void setUp() {
        controller = new JobController(jobService, preparationService, jobRepo, outputRepo,
                deliveryRepairService, new AppProps(), ffmpeg);
    }

    @Test
    void submitWithoutAdmissionIsRejectedBeforeCreatingJob() {
        JobController.SubmitReq req = new JobController.SubmitReq();
        req.setProjectId(7L);
        R<Job> result = controller.submit(req);
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("干跑"));
        verify(jobService, never()).submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submitWithAdmissionCallsServiceIncludingVariant() {
        JobController.SubmitReq req = new JobController.SubmitReq();
        req.setAdmission(new AdmissionSnapshot());
        req.setVariant(4);
        when(jobService.submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.any())).thenReturn(new Job());
        R<Job> result = controller.submit(req);
        assertTrue(result.isOk());
        verify(jobService).submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void batchDeleteRemovesTerminalJobsAndSkipsUnknownIds() {
        JobController.BatchDeleteReq req = new JobController.BatchDeleteReq();
        req.setIds(List.of(10L, 99L, 10L));
        Job job = new Job();
        job.setId(10L);
        when(jobRepo.findById(10L)).thenReturn(Optional.of(job));
        when(jobRepo.findById(99L)).thenReturn(Optional.empty());

        R<Map<String, Object>> result = controller.batchDelete(req);

        assertTrue(result.isOk());
        assertEquals(1, result.getData().get("deleted"));
        List<?> skipped = (List<?>) result.getData().get("skipped");
        assertEquals(1, skipped.size());
        verify(jobService).deleteJob(10L);
    }

    @Test
    void batchDeleteKeepsActiveJobsWhenServiceRejectsThem() {
        JobController.BatchDeleteReq req = new JobController.BatchDeleteReq();
        req.setIds(List.of(20L));
        Job job = new Job();
        job.setId(20L);
        when(jobRepo.findById(20L)).thenReturn(Optional.of(job));
        doThrow(new IllegalArgumentException("运行中或已暂停的任务请先取消，确认停止后再删除记录"))
                .when(jobService).deleteJob(20L);

        R<Map<String, Object>> result = controller.batchDelete(req);

        assertTrue(result.isOk());
        assertEquals(0, result.getData().get("deleted"));
        List<?> skipped = (List<?>) result.getData().get("skipped");
        assertEquals(1, skipped.size());
        assertFalse(skipped.isEmpty());
    }

    @Test
    void jobOperationEndpointsDelegateAndReturnOk() {
        assertTrue(controller.cancel(21L).isOk());
        assertTrue(controller.pause(21L).isOk());
        assertTrue(controller.resume(21L).isOk());
        assertTrue(controller.retryFailed(21L).isOk());

        verify(jobService).cancel(21L);
        verify(jobService).pause(21L);
        verify(jobService).resume(21L);
        verify(jobService).retryFailedItems(21L);
    }

    @Test
    void retryFailedEndpointPropagatesServiceBoundaryErrorAsException() {
        doThrow(new IllegalArgumentException("任务正在执行"))
                .when(jobService).retryFailedItems(22L);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.retryFailed(22L));
    }

    @Test
    void pauseAndResumeEndpointPropagateInvalidStateErrors() {
        doThrow(new IllegalArgumentException("已结束的任务不能暂停")).when(jobService).pause(23L);
        doThrow(new IllegalArgumentException("只有已暂停的任务可以继续")).when(jobService).resume(24L);

        assertEquals("已结束的任务不能暂停", org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> controller.pause(23L)).getMessage());
        assertEquals("只有已暂停的任务可以继续", org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> controller.resume(24L)).getMessage());
    }

    @Test
    void prepareReturnsAsyncTaskSnapshotWithoutWaiting() {
        RenderPreparationService.PrepareRequest req = new RenderPreparationService.PrepareRequest();
        req.setProjectId(7L);
        RenderPreparationService.PrepareResult snapshot = new RenderPreparationService.PrepareResult();
        snapshot.setId(42L);
        snapshot.setStatus("running");
        when(preparationService.prepare(req)).thenReturn(snapshot);

        R<RenderPreparationService.PrepareResult> result = controller.prepare(req);

        assertTrue(result.isOk());
        assertEquals(42L, result.getData().getId());
        assertEquals("running", result.getData().getStatus());
    }

    @Test
    void prepareStatusExposesPollingSnapshotOrFailsForUnknownTask() {
        RenderPreparationService.PrepareResult done = new RenderPreparationService.PrepareResult();
        done.setId(42L);
        done.setStatus("done");
        done.setReady(true);
        done.setFinalGap(new MaterialGapService.MaterialGapResult());
        when(preparationService.status(42L)).thenReturn(done);
        when(preparationService.status(999L)).thenThrow(new IllegalArgumentException("准备任务不存在"));

        R<RenderPreparationService.PrepareResult> found = controller.prepareStatus(42L);
        assertTrue(found.isOk());
        assertEquals("done", found.getData().getStatus());
        assertTrue(found.getData().isReady());
        assertNotNull(found.getData().getFinalGap());

        R<RenderPreparationService.PrepareResult> missing = controller.prepareStatus(999L);
        assertFalse(missing.isOk());
        assertEquals("准备任务不存在", missing.getMessage());
    }

    @Test
    void prepareListReturnsRecentTasks() {
        RenderPreparationService.PrepareResult first = new RenderPreparationService.PrepareResult();
        first.setId(2L);
        first.setStatus("running");
        RenderPreparationService.PrepareResult second = new RenderPreparationService.PrepareResult();
        second.setId(1L);
        second.setStatus("timedout");
        when(preparationService.recent()).thenReturn(List.of(first, second));

        R<List<RenderPreparationService.PrepareResult>> result = controller.prepareList();

        assertTrue(result.isOk());
        assertEquals(2, result.getData().size());
        assertEquals(2L, result.getData().get(0).getId());
    }
}
