package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Job;
import com.douyin.mixcut.domain.JobStatus;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JobServiceReliabilityTest {
    @Mock private JobRepo jobRepo;
    @Mock private Executor renderExecutor;
    @Mock private ProcessRegistry processRegistry;
    private final JobOutputServiceDeps deps = new JobOutputServiceDeps() {};

    private JobService service;

    @BeforeEach
    void setUp() {
        service = new JobService(jobRepo, deps.outputRepo(), deps.workflowRepo(), deps.projectRepo(), deps.folderRepo(),
                deps.skillEngine(), deps.renderService(), deps.copyService(), deps.narrationService(),
                deps.materialDiagnosisService(), deps.materialStore(), deps.editorialBriefService(),
                deps.deliveryRepairService(), deps.outputVersionRepo(), deps.outputRepairRepo(), new AppProps(), renderExecutor);
        ReflectionTestUtils.setField(service, "processRegistry", processRegistry);
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
    void watchdogDistinguishesTotalTimeout() {
        Job job = runningJob(9L, 7200, 60);
        job.setCreatedAt(LocalDateTime.now().minusSeconds(120));
        job.setLastActivityAt(LocalDateTime.now());
        when(jobRepo.findByStatusOrderByIdAsc(JobStatus.running.name())).thenReturn(List.of(job));
        when(jobRepo.failTimedOutRunningJob(eq(9L), anyString(), contains("job_timeout"), any(), any())).thenReturn(1);

        service.markStaleJobs();

        verify(jobRepo).failTimedOutRunningJob(eq(9L), eq("任务总时限超时"), contains("job_timeout"), any(), any());
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
