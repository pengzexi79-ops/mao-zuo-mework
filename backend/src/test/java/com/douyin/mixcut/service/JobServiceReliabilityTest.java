package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Job;
import com.douyin.mixcut.domain.JobOutput;
import com.douyin.mixcut.domain.JobStatus;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.dto.EffectiveRenderConfig;
import com.douyin.mixcut.repository.Repositories.JobOutputRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.douyin.mixcut.external.ProcessRegistry;
import com.douyin.mixcut.repository.Repositories.JobRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JobServiceReliabilityTest {
    @Mock private JobRepo jobRepo;
    @Mock private JobOutputRepo outputRepo;
    @Mock private Executor renderExecutor;
    @Mock private ProcessRegistry processRegistry;
    @Mock private RenderConfigResolver renderConfigResolver;
    @Mock private RenderAdmissionService renderAdmissionService;
    private JobOutputServiceDeps deps;

    private JobService service;

    @BeforeEach
    void setUp() {
        deps = new JobOutputServiceDeps() {};
        service = new JobService(jobRepo, outputRepo, deps.workflowRepo(), deps.projectRepo(), deps.folderRepo(),
                deps.skillEngine(), deps.renderService(), deps.copyService(), deps.narrationService(),
                deps.materialDiagnosisService(), deps.materialStore(), deps.editorialBriefService(),
                deps.deliveryRepairService(), deps.outputVersionRepo(), deps.outputRepairRepo(), new AppProps(),
                renderConfigResolver, renderAdmissionService, renderExecutor);
        ReflectionTestUtils.setField(service, "processRegistry", processRegistry);
    }

    @Test
    void repairInvalidatesFrozenAdmissionSnapshotButKeepsEffectiveParams() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        MixParams original = new MixParams();
        root.set("effectiveParams", mapper.valueToTree(original));
        root.put("snapshotVersion", 1);
        var admission = mapper.createObjectNode();
        admission.put("status", "ready");
        admission.put("statusSignature", "sig");
        admission.put("configHash", "config");
        root.set("admissionSnapshot", admission);
        String updated = ReflectionTestUtils.invokeMethod(service, "updateFrozenParams", root.toString(), new MixParams());
        var updatedRoot = mapper.readTree(updated);
        assertEquals("invalidated_by_repair", updatedRoot.path("admissionSnapshot").path("status").asText());
        assertEquals("effective_params_changed_by_repair", updatedRoot.path("admissionInvalidatedReason").asText());
        org.junit.jupiter.api.Assertions.assertTrue(updatedRoot.path("effectiveParams").isObject());
        org.junit.jupiter.api.Assertions.assertTrue(updatedRoot.path("admissionSnapshot").path("statusSignature").isNull());
    }

    @Test
    void freezeSubmissionKeepsContinuousSchedulerFlagOutsideEffectiveParams() throws Exception {
        EffectiveRenderConfig admission = new EffectiveRenderConfig();
        admission.setParams(new MixParams());

        String frozen = ReflectionTestUtils.invokeMethod(service, "freezeSubmission", null, null,
                "{\"continuous\":true,\"format\":\"9:16\"}", admission);
        var root = new ObjectMapper().readTree(frozen);

        org.junit.jupiter.api.Assertions.assertTrue(root.path("continuous").asBoolean(),
                "continuous must survive the frozen job snapshot");
        org.junit.jupiter.api.Assertions.assertTrue(root.path("effectiveParams").isObject());
    }

    @Test
    void freezeSubmissionDefaultsFixedJobsToNonContinuous() throws Exception {
        EffectiveRenderConfig admission = new EffectiveRenderConfig();
        admission.setParams(new MixParams());

        String frozen = ReflectionTestUtils.invokeMethod(service, "freezeSubmission", null, null,
                "{\"format\":\"9:16\"}", admission);
        var root = new ObjectMapper().readTree(frozen);

        org.junit.jupiter.api.Assertions.assertFalse(root.path("continuous").asBoolean(),
                "fixed-count jobs must not inherit continuous mode");
    }

    @Test
    void legacyNestedContinuousFlagRemainsResumable() {
        Job job = new Job();
        job.setParams("{\"effectiveParams\":{\"continuous\":true}}");

        org.junit.jupiter.api.Assertions.assertTrue(job.isContinuous(),
                "legacy snapshots must still identify continuous jobs");
    }

    @Test
    void claimLeaseDoesNotClaimNonPendingJob() {
        Job running = runningJob(6L, 900, 7200);
        when(jobRepo.findById(6L)).thenReturn(Optional.of(running));

        service.dispatch(6L);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(renderExecutor).execute(task.capture());
        task.getValue().run();

        verify(jobRepo, never()).claimPendingJob(anyLong(), anyString(), any(), any());
    }

    @Test
    void dispatchRejectKeepsPendingAndRecordsRetryableFailure() {
        Job pending = pendingJob(13L, 2);
        when(jobRepo.findById(13L)).thenReturn(Optional.of(pending));
        doThrow(new java.util.concurrent.RejectedExecutionException("full"))
                .when(renderExecutor).execute(any(Runnable.class));

        service.dispatch(13L);

        assertEquals(JobStatus.pending.name(), pending.getStatus());
        verify(jobRepo).save(pending);
        verify(renderExecutor).execute(any(Runnable.class));
    }

    @Test
    void watchdogFailsDispatchedStaleJobWithoutUsingInMemoryDispatchAsExemption() {
        Job job = runningJob(7L, 900, 7200);
        job.setLastActivityAt(LocalDateTime.now().minusSeconds(1200));
        when(jobRepo.findByStatusOrderByIdAsc(JobStatus.running.name())).thenReturn(List.of(job));
        when(jobRepo.failStaleRunningJob(eq(7L), anyString(), contains("heartbeat_timeout"), any(), any())).thenReturn(1);

        service.markStaleJobs();

        verify(jobRepo).failStaleRunningJob(eq(7L), eq("任务心跳超时"), contains("heartbeat_timeout"), any(), any());
    }

    @Test
    void watchdogLeavesFreshHeartbeatAlone() {
        Job job = runningJob(8L, 900, 7200);
        job.setLastActivityAt(LocalDateTime.now().minusSeconds(10));
        when(jobRepo.findByStatusOrderByIdAsc(JobStatus.running.name())).thenReturn(List.of(job));

        service.markStaleJobs();

        verify(jobRepo, never()).failStaleRunningJob(anyLong(), anyString(), anyString(), any(), any());
        verify(jobRepo, never()).failTimedOutRunningJob(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void cancellingRunningJobCancelsItsOwnedProcessContext() {
        Job job = runningJob(10L, 900, 7200);
        when(jobRepo.findById(10L)).thenReturn(Optional.of(job));
        when(jobRepo.transitionCancelled(eq(10L), anyList(), anyInt(), anyInt(), anyString(), any())).thenReturn(1);
        ProcessRegistry.CancellationContext context = new ProcessRegistry().create("job:10");
        when(processRegistry.create("job:10")).thenReturn(context);
        ReflectionTestUtils.invokeMethod(service, "processContext", 10L);

        service.cancel(10L);

        verify(processRegistry).cancel(context);
    }

    @Test
    void pausingRunningJobCancelsItsOwnedProcessContext() {
        Job job = runningJob(11L, 900, 7200);
        when(jobRepo.findById(11L)).thenReturn(Optional.of(job));
        when(jobRepo.transitionPaused(eq(11L), anyList(), anyInt(), anyInt(), anyString(), any())).thenReturn(1);
        ProcessRegistry.CancellationContext context = new ProcessRegistry().create("job:11");
        when(processRegistry.create("job:11")).thenReturn(context);
        ReflectionTestUtils.invokeMethod(service, "processContext", 11L);

        service.pause(11L);

        verify(processRegistry).cancel(context);
    }

    @Test
    void resumeStartsFreshProcessContextForAwaitingDecisionGeneration() {
        Job job = runningJob(12L, 900, 7200);
        job.setStatus(JobStatus.awaiting_decision.name());
        when(jobRepo.findById(12L)).thenReturn(Optional.of(job));
        ProcessRegistry.CancellationContext previous = new ProcessRegistry().create("job:12");
        ProcessRegistry.CancellationContext fresh = new ProcessRegistry().create("job:12-fresh");
        when(processRegistry.replace("job:12")).thenReturn(fresh);
        ReflectionTestUtils.setField(service, "renderContexts", new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of(12L, previous)));

        service.resume(12L);

        verify(processRegistry).replace("job:12");
        verify(jobRepo).save(job);
    }

    @Test
    void recoveryRequeuesStaleRunningJobAtSuccessfulCheckpoint() {
        Job stale = runningJob(14L, 60, 7200);
        stale.setTotal(2);
        stale.setCount(2);
        stale.setLastActivityAt(LocalDateTime.now().minusSeconds(120));
        JobOutput first = new JobOutput();
        first.setJobId(14L);
        first.setIdx(1);
        first.setQcStatus("pass");
        first.setFilePath("/tmp/one.mp4");
        when(jobRepo.findByStatusOrderByIdAsc(JobStatus.pending.name())).thenReturn(List.of());
        when(jobRepo.findByStatusOrderByIdAsc(JobStatus.running.name())).thenReturn(List.of(stale));
        when(jobRepo.findById(14L)).thenReturn(Optional.of(stale));
        when(outputRepo.findByJobIdOrderByIdxAsc(14L)).thenReturn(List.of(first));
        when(jobRepo.invalidateLease(eq(14L), anyList())).thenReturn(1);
        doAnswer(invocation -> null).when(renderExecutor).execute(any(Runnable.class));

        service.recoverInterruptedJobs();

        assertEquals(JobStatus.pending.name(), stale.getStatus());
        assertEquals(1, stale.getCurrent());
        assertEquals(50, stale.getProgress());
        verify(jobRepo).invalidateLease(eq(14L), eq(List.of(JobStatus.running.name())));
        verify(jobRepo).save(stale);
        verify(renderExecutor).execute(any(Runnable.class));
    }

    @Test
    void retryFailedItemsRejectsRunningJobWithoutChangingState() {
        Job running = runningJob(15L, 900, 7200);
        when(jobRepo.findById(15L)).thenReturn(Optional.of(running));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.retryFailedItems(15L));

        verify(jobRepo, never()).save(any(Job.class));
        verify(renderExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void retryFailedItemsReplacesCancelledProcessContextBeforeDispatch() {
        Job failed = pendingJob(17L, 2);
        failed.setStatus(JobStatus.failed.name());
        when(jobRepo.findById(17L)).thenReturn(Optional.of(failed));
        when(outputRepo.findByJobIdOrderByIdxAsc(17L)).thenReturn(List.of());
        ProcessRegistry.CancellationContext cancelledContext = new ProcessRegistry().create("job:17");
        ProcessRegistry.CancellationContext freshContext = new ProcessRegistry().create("job:17-fresh");
        when(processRegistry.replace("job:17")).thenReturn(freshContext);
        ReflectionTestUtils.setField(service, "renderContexts",
                new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of(17L, cancelledContext)));

        service.retryFailedItems(17L);

        verify(processRegistry).replace("job:17");
        verify(jobRepo).save(failed);
        verify(renderExecutor).execute(any(Runnable.class));
    }

    @Test
    void fixedBatchCheckpointContractCountsOnlySuccessfulOutputsWithinTotal() {
        JobOutput valid = new JobOutput();
        valid.setIdx(1);
        valid.setQcStatus("pass");
        valid.setFilePath("/tmp/one.mp4");
        JobOutput qcFailed = new JobOutput();
        qcFailed.setIdx(2);
        qcFailed.setQcStatus("fail");
        qcFailed.setFilePath("/tmp/two.mp4");
        JobOutput outsideBatch = new JobOutput();
        outsideBatch.setIdx(4);
        outsideBatch.setQcStatus("pass");
        outsideBatch.setFilePath("/tmp/four.mp4");
        when(outputRepo.findByJobIdOrderByIdxAsc(16L)).thenReturn(List.of(valid, qcFailed, outsideBatch));

        java.util.Set<Integer> checkpoints = service.successfulIndexes(16L, 3);

        assertEquals(java.util.Set.of(1), checkpoints);
    }

    @Test
    void watchdogDistinguishesTotalTimeout() {
        Job job = runningJob(9L, 7200, 60);
        job.setCreatedAt(LocalDateTime.now().minusSeconds(120));
        job.setLastActivityAt(LocalDateTime.now());
        when(jobRepo.findByStatusOrderByIdAsc(JobStatus.running.name())).thenReturn(List.of(job));
        when(jobRepo.failTimedOutRunningJob(eq(9L), anyString(), contains("job_timeout"), any(), any())).thenReturn(1);

        service.markStaleJobs();

        verify(jobRepo).failTimedOutRunningJob(eq(9L), eq("任务总时限超时"), contains("job_timeout"), any(), any());
    }

    private Job pendingJob(Long id, int total) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(JobStatus.pending.name());
        job.setTotal(total);
        job.setCount(total);
        job.setStaleAfterSec(900);
        job.setTimeoutSec(7200);
        return job;
    }

    private Job runningJob(Long id, int stale, int timeout) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(JobStatus.running.name());
        job.setStaleAfterSec(stale);
        job.setTimeoutSec(timeout);
        job.setCreatedAt(LocalDateTime.now().minusSeconds(5));
        job.setLastActivityAt(LocalDateTime.now());
        job.setTotal(1);
        job.setCount(1);
        job.setExecutionEpoch(1L);
        job.setLeaseToken("token");
        return job;
    }

    /** Keeps the constructor setup local without exposing credentials or application state. */
    interface JobOutputServiceDeps {
        default com.douyin.mixcut.repository.Repositories.JobOutputRepo outputRepo() { return mock(com.douyin.mixcut.repository.Repositories.JobOutputRepo.class); }
        default com.douyin.mixcut.repository.Repositories.WorkflowRepo workflowRepo() { return mock(com.douyin.mixcut.repository.Repositories.WorkflowRepo.class); }
        default com.douyin.mixcut.repository.Repositories.ProjectRepo projectRepo() { return mock(com.douyin.mixcut.repository.Repositories.ProjectRepo.class); }
        default com.douyin.mixcut.repository.Repositories.MaterialFolderRepo folderRepo() { return mock(com.douyin.mixcut.repository.Repositories.MaterialFolderRepo.class); }
        default SkillEngine skillEngine() { return mock(SkillEngine.class); }
        default RenderService renderService() { return mock(RenderService.class); }
        default CopyService copyService() { return mock(CopyService.class); }
        default NarrationService narrationService() { return mock(NarrationService.class); }
        default MaterialDiagnosisService materialDiagnosisService() { return mock(MaterialDiagnosisService.class); }
        default com.douyin.mixcut.repository.MaterialStore materialStore() { return mock(com.douyin.mixcut.repository.MaterialStore.class); }
        default EditorialBriefService editorialBriefService() { return mock(EditorialBriefService.class); }
        default DeliveryRepairService deliveryRepairService() { return mock(DeliveryRepairService.class); }
        default com.douyin.mixcut.repository.Repositories.OutputVersionRepo outputVersionRepo() { return mock(com.douyin.mixcut.repository.Repositories.OutputVersionRepo.class); }
        default com.douyin.mixcut.repository.Repositories.OutputRepairRepo outputRepairRepo() { return mock(com.douyin.mixcut.repository.Repositories.OutputRepairRepo.class); }
    }
}
