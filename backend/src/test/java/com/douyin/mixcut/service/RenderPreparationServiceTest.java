package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.CrawlTask;
import com.douyin.mixcut.domain.JobStatus;
import com.douyin.mixcut.domain.PreparationTask;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.CrawlJob;
import com.douyin.mixcut.domain.UseCase;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.repository.Repositories.CrawlJobRepo;
import com.douyin.mixcut.repository.Repositories.CrawlTaskRepo;
import com.douyin.mixcut.repository.Repositories.PreparationTaskRepo;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the async preparation-task workflow:
 * fast POST contract, persistent polling state, bounded crawl-queue wait, local-only fast completion.
 */
@ExtendWith(MockitoExtension.class)
class RenderPreparationServiceTest {
    @Mock private MaterialGapService gapService;
    @Mock private ProjectRepo projectRepo;
    @Mock private CrawlJobRepo crawlJobRepo;
    @Mock private CrawlTaskRepo crawlTaskRepo;
    @Mock private CrawlJobService crawlJobService;
    @Mock private AiService aiService;
    @Mock private MaterialAnalysisService materialAnalysisService;
    @Mock private PreparationTaskRepo taskRepo;
    @Mock private DataSource dataSource;

    private final ObjectMapper om = new ObjectMapper();
    private final AppProps props = new AppProps();

    /** Inline executor: the pipeline runs synchronously, deterministic for flow assertions. */
    private final Executor inline = Runnable::run;

    @BeforeEach
    void resetProps() {
        props.setPexelsApiKey("");
    }

    private RenderPreparationService newService(Executor executor, AtomicReference<PreparationTask> current) {
        when(taskRepo.save(any(PreparationTask.class))).thenAnswer(inv -> {
            PreparationTask task = inv.getArgument(0);
            if (task.getId() == null) task.setId(1L);
            current.set(task);
            return task;
        });
        when(taskRepo.findById(any(Long.class))).thenAnswer(inv ->
                current.get() == null ? Optional.empty() : Optional.of(current.get()));
        return new RenderPreparationService(gapService, props, projectRepo, crawlJobRepo, crawlTaskRepo,
                crawlJobService, aiService, materialAnalysisService, taskRepo, dataSource, om, executor);
    }

    @Test
    void keepsRenderLocalWhenExistingMaterialsAreSufficient() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(true, "护肤 精华"));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(new MixParams());

        RenderPreparationService.PrepareResult result = service.prepare(request);

        assertEquals(PreparationTask.STATUS_DONE, result.getStatus());
        assertTrue(result.isReady());
        assertFalse(result.isTimedOut());
        verify(gapService, never()).autoFill(any());
    }

    @Test
    void assistModeNeverAutoFillsEvenWhenPublicSourcesAreSelected() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = new MixParams();
        params.setMaterialSourceMode("builtin");
        params.setAutonomyMode("assist");
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(
                gap(false, "食品 饮料"), gap(false, "食品 饮料"));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);

        RenderPreparationService.PrepareResult result = service.prepare(request);

        verify(gapService, never()).autoFill(any());
        assertTrue(result.getStages().stream().anyMatch(stage ->
                "补齐公开素材".equals(stage.get("name"))
                        && "skipped".equals(stage.get("status"))
                        && String.valueOf(stage.get("message")).contains("辅助模式")));
    }

    @Test
    void doesNotUsePublicFillForProductAndEndcardRoleGaps() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(
                gap(true, "食品 饮料", List.of("product", "endcard")),
                gap(true, "食品 饮料", List.of("product", "endcard")));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);

        RenderPreparationService.PrepareResult result = service.prepare(request);

        verify(gapService, never()).autoFill(any());
        assertTrue(result.getStages().stream().anyMatch(stage ->
                "补齐公开素材".equals(stage.get("name")) && "skipped".equals(stage.get("status"))));
    }

    @Test
    void localOnlyModeDoesNotFillStructuralRoleGaps() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        params.setMaterialSourceMode("local");
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(
                gap(true, "食品 饮料", List.of("endcard")),
                gap(true, "食品 饮料", List.of("endcard")));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);

        RenderPreparationService.PrepareResult result = service.prepare(request);

        verify(gapService, never()).autoFill(any());
        assertTrue(result.getStages().stream().anyMatch(stage ->
                "补齐公开素材".equals(stage.get("name")) && "skipped".equals(stage.get("status"))));
    }

    @Test
    void fillsOnlyThroughExistingPublicSourceRequestThenRechecksPool() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(false, "食品 饮料"), gap(true, "食品 饮料"));
        MaterialGapService.AutoFillResult autoFill = new MaterialGapService.AutoFillResult();
        autoFill.setAny(false);
        when(gapService.autoFill(any())).thenReturn(autoFill);
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);
        request.setWaitSeconds(0);

        RenderPreparationService.PrepareResult result = service.prepare(request);

        ArgumentCaptor<MaterialGapService.AutoFillRequest> captor = ArgumentCaptor.forClass(MaterialGapService.AutoFillRequest.class);
        verify(gapService).autoFill(captor.capture());
        assertEquals(List.of("wikimedia", "archive"), captor.getValue().getSources(),
                "auto-fill must never include Mixkit: manual-import only");
        assertEquals("食品 饮料", captor.getValue().getKeyword());
        assertEquals(PreparationTask.STATUS_DONE, result.getStatus());
        assertTrue(result.isReady());
        assertFalse(result.isTimedOut());
    }

    @Test
    void addsPexelsOnlyWhenAnOfficialKeyIsConfigured() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        props.setPexelsApiKey("test-key");
        MixParams params = automaticParams();
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(false, "食品 饮料"), gap(true, "食品 饮料"));
        MaterialGapService.AutoFillResult autoFill = new MaterialGapService.AutoFillResult();
        when(gapService.autoFill(any())).thenReturn(autoFill);
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);

        service.prepare(request);

        ArgumentCaptor<MaterialGapService.AutoFillRequest> captor = ArgumentCaptor.forClass(MaterialGapService.AutoFillRequest.class);
        verify(gapService).autoFill(captor.capture());
        assertEquals(List.of("wikimedia", "archive", "pexels"), captor.getValue().getSources());
    }

    @Test
    void localOnlyModeCompletesFastWithoutExternalSources() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        params.setMaterialSourceMode("local");
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(false, "食品 饮料"), gap(true, "食品 饮料"));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);

        RenderPreparationService.PrepareResult result = service.prepare(request);

        assertEquals(PreparationTask.STATUS_DONE, result.getStatus());
        assertEquals(0, result.getWaitedSeconds());
        assertFalse(result.isTimedOut());
        verify(gapService, never()).autoFill(any());
        assertTrue(result.getStages().stream().anyMatch(stage ->
                "补齐公开素材".equals(stage.get("name")) && "skipped".equals(stage.get("status"))));
        assertEquals(PreparationTask.STATUS_DONE, current.get().getStatus());
    }

    @Test
    void boundedWaitMarksTimedOutWhenCrawlJobStillPending() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(false, "食品 饮料"), gap(true, "食品 饮料"));
        MaterialGapService.AutoFillResult autoFill = new MaterialGapService.AutoFillResult();
        autoFill.setAny(true);
        autoFill.setCrawlJobIds(List.of(100L));
        autoFill.setTotalItemsQueued(4);
        when(gapService.autoFill(any())).thenReturn(autoFill);
        when(crawlJobRepo.findById(100L)).thenReturn(Optional.of(crawlJob(JobStatus.pending.name())));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);
        request.setWaitSeconds(0); // 有界等待：0 秒立即超时，绝不无限阻塞

        RenderPreparationService.PrepareResult result = service.prepare(request);

        assertEquals(PreparationTask.STATUS_TIMEDOUT, result.getStatus());
        assertTrue(result.isTimedOut());
        assertEquals(0, result.getWaitedSeconds());
        assertTrue(result.getStages().stream().anyMatch(stage ->
                "等待公开素材".equals(stage.get("name")) && "warning".equals(stage.get("status"))));
        // 终态与阶段快照已持久化，轮询接口可读到
        assertEquals(PreparationTask.STATUS_TIMEDOUT, current.get().getStatus());
        assertTrue(current.get().getTimedOut());
        assertNotNull(current.get().getStages());
        assertTrue(current.get().getStages().contains("等待公开素材"));
        assertNotNull(current.get().getInitialGap());
    }

    @Test
    void waitsUntilQueuedCrawlJobsFinishAndExposesFinalGap() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(false, "食品 饮料"), gap(true, "食品 饮料"));
        MaterialGapService.AutoFillResult autoFill = new MaterialGapService.AutoFillResult();
        autoFill.setAny(true);
        autoFill.setCrawlJobIds(List.of(100L));
        autoFill.setTotalItemsQueued(4);
        when(gapService.autoFill(any())).thenReturn(autoFill);
        when(crawlJobRepo.findById(100L)).thenReturn(Optional.of(crawlJob(JobStatus.done.name())));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);
        request.setWaitSeconds(30);

        RenderPreparationService.PrepareResult result = service.prepare(request);

        assertEquals(PreparationTask.STATUS_DONE, result.getStatus());
        assertFalse(result.isTimedOut());
        assertEquals(0, result.getWaitedSeconds());
        assertNotNull(result.getFinalGap());
        assertTrue(result.getFinalGap().isSufficient());
        assertTrue(result.getStages().stream().anyMatch(stage ->
                "等待公开素材".equals(stage.get("name")) && "done".equals(stage.get("status"))));
        // 轮询接口返回与提交快照一致的持久化终态
        RenderPreparationService.PrepareResult polled = service.status(result.getId());
        assertEquals(PreparationTask.STATUS_DONE, polled.getStatus());
        assertNotNull(polled.getFinalGap());
        assertEquals(List.of(100L), polled.getCrawlJobIds());
        assertEquals(result.getStages().size(), polled.getStages().size());
    }

    @Test
    void reportsDownloadAndQualityAdmissionFailuresInPreparationStages() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(false, "食品 饮料"), gap(false, "食品 饮料"));
        MaterialGapService.AutoFillResult autoFill = new MaterialGapService.AutoFillResult();
        autoFill.setAny(true);
        autoFill.setCrawlJobIds(List.of(100L));
        autoFill.setTotalItemsQueued(2);
        when(gapService.autoFill(any())).thenReturn(autoFill);
        when(crawlJobRepo.findById(100L)).thenReturn(Optional.of(crawlJob(JobStatus.done.name())));
        CrawlTask admitted = new CrawlTask();
        admitted.setStatus(JobStatus.done.name());
        admitted.setMessage("已入库：food-broll.mp4");
        CrawlTask rejected = new CrawlTask();
        rejected.setStatus(JobStatus.done.name());
        rejected.setMessage("已下载但未通过质量准入：画面冻结/几乎静止");
        when(crawlTaskRepo.findByJobIdOrderByIdxAsc(100L)).thenReturn(List.of(admitted, rejected));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setUseAi(false);
        request.setParams(params);

        RenderPreparationService.PrepareResult result = service.prepare(request);

        assertEquals(PreparationTask.STATUS_DONE, result.getStatus());
        assertFalse(result.isReady(), "partial import failures must not pretend the material gap is filled");
        assertTrue(result.getStages().stream().anyMatch(stage ->
                "检查入库结果".equals(stage.get("name"))
                        && "warning".equals(stage.get("status"))
                        && String.valueOf(stage.get("message")).contains("质量准入")));
    }

    @Test
    void postReturnsQuicklyWhileCrawlWaitRunsInBackground() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(false, "食品 饮料"), gap(true, "食品 饮料"));
        MaterialGapService.AutoFillResult autoFill = new MaterialGapService.AutoFillResult();
        autoFill.setAny(true);
        autoFill.setCrawlJobIds(List.of(100L));
        autoFill.setTotalItemsQueued(4);
        when(gapService.autoFill(any())).thenReturn(autoFill);
        when(crawlJobRepo.findById(100L)).thenReturn(Optional.of(crawlJob(JobStatus.pending.name())));
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(5);
        pool.setThreadNamePrefix("prepare-test-");
        pool.initialize();
        try {
            RenderPreparationService service = newService(pool, current);
            RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
            request.setUseAi(false);
            request.setParams(params);
            request.setWaitSeconds(2); // 后台任务最多等 2 秒，绝不拖住请求线程

            long started = System.nanoTime();
            RenderPreparationService.PrepareResult result = service.prepare(request);
            long tookMs = (System.nanoTime() - started) / 1_000_000;

            assertTrue(tookMs < 1500, "POST prepare 应毫秒级返回，实际 " + tookMs + "ms");
            assertNotNull(result.getId());
            assertEquals(PreparationTask.STATUS_RUNNING, result.getStatus());
        } finally {
            pool.shutdown();
        }
        // 后台任务有 2 秒有界上限；等它自然到达终态后再退出，避免测试 JVM 悬挂/多余桩误报
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline
                && PreparationTask.STATUS_RUNNING.equals(current.get().getStatus())) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(PreparationTask.STATUS_TIMEDOUT, current.get().getStatus());
    }

    @Test
    void aiRefinedKeywordIsPersistedAndUsedForAutofill() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        MixParams params = automaticParams();
        Project project = new Project();
        project.setId(1L);
        project.setCategory("护肤");
        project.setProduct("精华");
        when(projectRepo.findById(1L)).thenReturn(Optional.of(project));
        when(gapService.analyze(any(), any(MixParams.class))).thenReturn(gap(false, "护肤 精华"), gap(true, "护肤 精华"));
        MaterialGapService.AutoFillResult autoFill = new MaterialGapService.AutoFillResult();
        autoFill.setAny(false);
        when(gapService.autoFill(any())).thenReturn(autoFill);
        when(aiService.ready()).thenReturn(true);
        when(aiService.ask(any(UseCase.class), anyString(), anyString(), anyDouble(), anyInt(), any()))
                .thenReturn(new AiService.Answer(true, "护肤 精华 紧致", null, "mock", "mock"));
        RenderPreparationService service = newService(inline, current);
        RenderPreparationService.PrepareRequest request = new RenderPreparationService.PrepareRequest();
        request.setProjectId(1L);
        request.setUseAi(true);
        request.setParams(params);

        RenderPreparationService.PrepareResult result = service.prepare(request);

        assertTrue(result.isAiUsed());
        assertEquals("护肤 精华 紧致", result.getKeyword());
        assertTrue(current.get().getAiUsed());
        assertEquals("护肤 精华 紧致", current.get().getKeyword());
        ArgumentCaptor<MaterialGapService.AutoFillRequest> captor = ArgumentCaptor.forClass(MaterialGapService.AutoFillRequest.class);
        verify(gapService).autoFill(captor.capture());
        assertEquals("护肤 精华 紧致", captor.getValue().getKeyword());
    }

    @Test
    void reclaimsInterruptedPreparationTaskAfterHeartbeatExpires() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        RenderPreparationService service = recoveryService();
        PreparationTask task = new PreparationTask();
        task.setId(88L);
        task.setStatus(PreparationTask.STATUS_RUNNING);
        task.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        task.setLastActivityAt(LocalDateTime.now().minusMinutes(10));
        current.set(task);
        when(taskRepo.findByStatusOrderByIdAsc(PreparationTask.STATUS_RUNNING)).thenReturn(List.of(task));

        service.recoverStaleRunning();

        assertEquals(PreparationTask.STATUS_FAILED, task.getStatus());
        assertTrue(task.getError().contains("服务中断"));
        verify(taskRepo).save(task);
    }

    @Test
    void keepsRecentlyActivePreparationTaskRunning() {
        AtomicReference<PreparationTask> current = new AtomicReference<>();
        RenderPreparationService service = recoveryService();
        PreparationTask task = new PreparationTask();
        task.setId(89L);
        task.setStatus(PreparationTask.STATUS_RUNNING);
        task.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        task.setLastActivityAt(LocalDateTime.now().minusSeconds(1));
        current.set(task);
        when(taskRepo.findByStatusOrderByIdAsc(PreparationTask.STATUS_RUNNING)).thenReturn(List.of(task));

        service.recoverStaleRunning();

        assertEquals(PreparationTask.STATUS_RUNNING, task.getStatus());
        verify(taskRepo, never()).save(task);
    }

    private RenderPreparationService recoveryService() {
        return new RenderPreparationService(gapService, props, projectRepo, crawlJobRepo, crawlTaskRepo,
                crawlJobService, aiService, materialAnalysisService, taskRepo, dataSource, om, inline);
    }

    private MixParams automaticParams() {
        MixParams params = new MixParams();
        params.setAutonomyMode("auto");
        params.setMaterialSourceMode("builtin");
        return params;
    }

    private MaterialGapService.MaterialGapResult gap(boolean sufficient, String keyword) {
        return gap(sufficient, keyword, List.of());
    }

    private MaterialGapService.MaterialGapResult gap(boolean sufficient, String keyword, List<String> missingRoles) {
        MaterialGapService.MaterialGapResult gap = new MaterialGapService.MaterialGapResult();
        gap.setSufficient(sufficient);
        gap.setProjectKeyword(keyword);
        gap.setMissingRoles(missingRoles);
        return gap;
    }

    private CrawlJob crawlJob(String status) {
        CrawlJob job = new CrawlJob();
        job.setId(100L);
        job.setStatus(status);
        return job;
    }
}
