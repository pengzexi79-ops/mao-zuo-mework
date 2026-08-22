package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.CrawlJob;
import com.douyin.mixcut.domain.CrawlTask;
import com.douyin.mixcut.domain.JobStatus;
import com.douyin.mixcut.domain.PreparationTask;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.UseCase;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.repository.Repositories.CrawlJobRepo;
import com.douyin.mixcut.repository.Repositories.CrawlTaskRepo;
import com.douyin.mixcut.repository.Repositories.PreparationTaskRepo;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Prepares one render request against the current project.
 *
 * <p>The service only uses the fixed public-source whitelist owned by MaterialGapService.
 * AI may refine a search phrase, but never controls URLs, sources, or download arguments.</p>
 *
 * <p>Async workflow: {@link #prepare(PrepareRequest)} persists a running
 * {@link PreparationTask} and returns immediately with its id/status; the bounded crawl-wait
 * pipeline runs on the dedicated prepareExecutor, never on the HTTP request thread. Progress is
 * persisted as JSON snapshots so {@link #status(Long)} polling exposes stages, final gap,
 * autofill results, and elapsed state. Local-only and already-sufficient runs complete in
 * milliseconds, so the POST response is a full done snapshot on those fast paths.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenderPreparationService {
    /**
     * 无人值守自动填充的固定免登录来源。Pexels 仅在用户提供自己的官方 API Key 后加入；
     * Mixkit 无公开 API 且服务条款不支持无人值守抓取，只允许手动导入，绝不在此列出。
     */
    private static final List<String> PUBLIC_SOURCES = List.of("wikimedia", "archive");
    private static final int DEFAULT_WAIT_SECONDS = 45;
    private static final int MAX_WAIT_SECONDS = 90;
    /** Longer than the bounded public wait plus AI request timeout; active tasks persist a heartbeat each second. */
    private static final int STALE_PREPARATION_SECONDS = 300;

    private static final String TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS preparation_task (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                status VARCHAR(32) NOT NULL DEFAULT 'running',
                project_id BIGINT,
                params JSON,
                use_ai TINYINT(1) NOT NULL DEFAULT 1,
                wait_seconds INT NOT NULL DEFAULT 45,
                keyword VARCHAR(255),
                ai_used TINYINT(1) NOT NULL DEFAULT 0,
                ready TINYINT(1) NOT NULL DEFAULT 0,
                timed_out TINYINT(1) NOT NULL DEFAULT 0,
                waited_seconds INT NOT NULL DEFAULT 0,
                crawl_job_ids JSON,
                stages JSON,
                initial_gap JSON,
                final_gap JSON,
                auto_fill JSON,
                error TEXT,
                last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_preparation_status (status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""";

    private final MaterialGapService gapService;
    private final AppProps props;
    private final ProjectRepo projectRepo;
    private final CrawlJobRepo crawlJobRepo;
    private final CrawlTaskRepo crawlTaskRepo;
    private final CrawlJobService crawlJobService;
    private final AiService aiService;
    private final MaterialAnalysisService materialAnalysisService;
    private final PreparationTaskRepo taskRepo;
    private final DataSource dataSource;
    private final ObjectMapper om;
    @Qualifier("prepareExecutor") private final Executor prepareExecutor;

    private volatile boolean tableReady = false;

    @Data
    public static class PrepareRequest {
        private Long projectId;
        private MixParams params;
        private Boolean useAi = true;
        /** Bounded server-side wait for existing public-source jobs; defaults to 45 seconds, clamped to [0,90]. */
        private Integer waitSeconds;
    }

    /** Polling/response view of one preparation task; identical shape to the legacy sync result plus task metadata. */
    @Data
    public static class PrepareResult {
        private Long id;
        /** running | done | timedout | failed */
        private String status;
        private String message;
        private long elapsedSec;
        private boolean aiUsed;
        private String keyword;
        private boolean ready;
        private boolean timedOut;
        private int waitedSeconds;
        private List<Long> crawlJobIds = new ArrayList<>();
        private MaterialGapService.MaterialGapResult initialGap;
        private MaterialGapService.MaterialGapResult finalGap;
        private MaterialGapService.AutoFillResult autoFill;
        private List<Map<String, Object>> stages = new ArrayList<>();
    }

    /** 幂等建表：与应用内其他迁移同款 DDL，DB 未就绪时静默降级，首次请求时重试。 */
    @PostConstruct
    void ensureTable() {
        if (tableReady) return;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(TABLE_DDL);
            tableReady = true;
            log.info("已确认出片准备任务表可用");
        } catch (Exception e) {
            log.warn("无法创建 preparation_task 表；准备请求时会重试：{}", e.toString());
        }
    }

    /**
     * Async entry point: persists a running preparation task and returns a snapshot immediately.
     * Never blocks the HTTP request thread while crawl jobs are running; the bounded wait pipeline
     * executes on {@code prepareExecutor}. Fast paths (local-only mode or sufficient local pool)
     * complete before the response is built, so those responses are full done snapshots.
     */
    public PrepareResult prepare(PrepareRequest request) {
        ensureTable();
        PrepareRequest req = request == null ? new PrepareRequest() : request;
        PreparationTask task = new PreparationTask();
        task.setStatus(PreparationTask.STATUS_RUNNING);
        task.setProjectId(req.getProjectId());
        task.setParams(writeJson(req.getParams()));
        task.setUseAi(req.getUseAi() == null || req.getUseAi());
        task.setWaitSeconds(boundWait(req.getWaitSeconds()));
        PreparationTask saved = taskRepo.save(task);
        try {
            prepareExecutor.execute(() -> run(saved.getId(), req));
        } catch (RuntimeException e) {
            // 饱和（队列满）或调度失败：落盘终态，轮询端可见而不是悬在 running
            log.warn("prepare dispatch failed for task {}: {}", saved.getId(), e.toString());
            markFailed(saved.getId(), "准备任务无法进入后台队列：" + concise(e));
        }
        return status(saved.getId());
    }

    /** Background worker; executes on prepareExecutor, never on an HTTP request thread. */
    public void run(Long taskId, PrepareRequest request) {
        PreparationTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null || !PreparationTask.STATUS_RUNNING.equals(task.getStatus())) return;
        try {
            if (isCancelled(task)) return;
            execute(task, request == null ? new PrepareRequest() : request);
        } catch (Exception e) {
            log.warn("preparation task {} failed: {}", taskId, e.toString());
            markFailed(taskId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Cancel a running preparation without cancelling shared crawl jobs. */
    public PrepareResult cancel(Long taskId) {
        ensureTable();
        PreparationTask task = taskRepo.findById(taskId).orElseThrow(() -> new IllegalArgumentException("准备任务不存在"));
        if (PreparationTask.STATUS_CANCELLED.equals(task.getStatus())) return snapshot(task);
        if (!PreparationTask.STATUS_RUNNING.equals(task.getStatus())) throw new IllegalArgumentException("当前准备任务已结束，不能取消");
        task.setStatus(PreparationTask.STATUS_CANCELLED);
        task.setError("已由用户取消准备；已发起的公开素材任务不会被连带取消");
        task.setLastActivityAt(LocalDateTime.now());
        taskRepo.save(task);
        return snapshot(task);
    }

    /** Polling view of one preparation task. */
    public PrepareResult status(Long taskId) {
        PreparationTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null) throw new IllegalArgumentException("准备任务不存在");
        return snapshot(task);
    }

    /** Recent preparation tasks, newest first (for discovery/debug UIs). */
    public List<PrepareResult> recent() {
        return taskRepo.findTop20ByOrderByIdDesc().stream().map(this::snapshot).toList();
    }

    /**
     * Marks preparation work abandoned by a backend interruption as terminal. A live task writes
     * lastActivityAt throughout its bounded wait, so this never reaps active preparation work.
     */
    @Scheduled(fixedDelayString = "${app.job-watchdog-delay-ms:30000}")
    public void recoverStaleRunning() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(STALE_PREPARATION_SECONDS);
            for (PreparationTask task : taskRepo.findByStatusOrderByIdAsc(PreparationTask.STATUS_RUNNING)) {
                LocalDateTime activity = task.getLastActivityAt() == null ? task.getCreatedAt() : task.getLastActivityAt();
                if (activity != null && activity.isAfter(cutoff)) continue;
                task.setStatus(PreparationTask.STATUS_FAILED);
                task.setError("后端服务中断，准备任务已自动回收；请重新开始出片");
                task.setLastActivityAt(LocalDateTime.now());
                taskRepo.save(task);
            }
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Setup mode: the task table is unavailable until MySQL is ready.
        }
    }

    /** Bounded pipeline core, shared by the background worker and focused tests. */
    PrepareResult execute(PreparationTask task, PrepareRequest request) {
        PrepareRequest req = request == null ? new PrepareRequest() : request;
        MixParams params = req.getParams() == null ? new MixParams() : req.getParams();
        PrepareResult result = new PrepareResult();
        result.setId(task.getId());
        result.setStatus(PreparationTask.STATUS_RUNNING);

        MaterialGapService.MaterialGapResult initial = gapService.analyze(req.getProjectId(), params);
        result.setInitialGap(initial);
        addStage(result, "匹配本地素材", initial.isSufficient()
                ? "本地素材已满足当前项目的基础时长要求"
                : "本地素材仍有缺口，准备检查公开素材源", "done");
        persist(task, result);

        Project project = req.getProjectId() == null ? null : projectRepo.findById(req.getProjectId()).orElse(null);
        String keyword = initial.getProjectKeyword();
        if ((task.getUseAi() == null || task.getUseAi()) && project != null && aiService.ready()) {
            String refined = aiKeyword(project, keyword);
            if (!refined.isBlank()) {
                keyword = refined;
                result.setAiUsed(true);
                addStage(result, "AI 识别项目", "已根据项目品类、产品与卖点生成公开素材检索词", "done");
            }
        }
        if (!result.isAiUsed()) {
            addStage(result, "识别项目", project == null
                    ? "未选择项目，使用当前出片参数生成检索词"
                    : "使用项目已有品类、产品和品牌字段生成检索词", "done");
        }
        result.setKeyword(keyword);
        persist(task, result);

        List<String> missingVisualRoles = initial.getMissingRoles() == null ? List.of()
                : initial.getMissingRoles().stream()
                .filter(MaterialGapService.VISUAL_AUTO_FILL_ROLES::contains)
                .distinct()
                .toList();
        boolean needsPublicFill = (!initial.isSufficient() && missingVisualRoles.isEmpty()) || !missingVisualRoles.isEmpty();
        boolean automaticFillAuthorized = "auto".equalsIgnoreCase(params.getAutonomyMode())
                || "autonomous".equalsIgnoreCase(params.getAutonomyMode());
        boolean localOnly = "local".equalsIgnoreCase(params.getMaterialSourceMode()) || !automaticFillAuthorized;
        if (needsPublicFill && localOnly) {
            String reason = "local".equalsIgnoreCase(params.getMaterialSourceMode())
                    ? "已选择仅本地素材，本次不会访问外部来源"
                    : "辅助模式不会自动访问外部公开来源；如需补齐，请手动使用公开素材填充或切换半自动/自主模式";
            addStage(result, "补齐公开素材", reason, "skipped");
        } else if (needsPublicFill) {
            List<String> autoSources = autoFillSources();
            String sourceMode = sourceDescription(autoSources);
            String roleGap = missingVisualRoles.isEmpty() ? "" : "时长已满足但缺少角色素材（"
                    + String.join("/", missingVisualRoles) + "），";
            addStage(result, "补齐公开素材", roleGap + "仅从 " + sourceMode + " 补齐；不绕过站点授权", "working");
            persist(task, result);
            MaterialGapService.AutoFillRequest fillReq = new MaterialGapService.AutoFillRequest();
            fillReq.setProjectId(req.getProjectId());
            fillReq.setParams(params);
            fillReq.setKeyword(keyword);
            fillReq.setSources(autoSources);
            fillReq.setPerSource(4);
            fillReq.setForce(true);
            fillReq.setRoles(missingVisualRoles);
            fillReq.setFolderId(autoFillFolderId(params));
            MaterialGapService.AutoFillResult autoFill = gapService.autoFill(fillReq);
            result.setAutoFill(autoFill);
            result.setCrawlJobIds(autoFill.getCrawlJobIds() == null ? List.of() : autoFill.getCrawlJobIds());
            if (autoFill.isAny()) {
                int limit = boundWait(req.getWaitSeconds());
                addStage(result, "等待公开素材", "已排队 " + autoFill.getTotalItemsQueued() + " 条公开素材任务，等待入库（上限 " + limit + " 秒）", "working");
                persist(task, result);
                WaitResult wait = waitForCrawlJobs(autoFill.getCrawlJobIds(), limit, task, result);
                result.setWaitedSeconds(wait.waitedSeconds());
                result.setTimedOut(wait.timedOut());
                updateStage(result, "等待公开素材", wait.message(), wait.timedOut() ? "warning" : "done");
                updateStage(result, "补齐公开素材",
                        "已从固定公开来源排队 " + autoFill.getTotalItemsQueued() + " 条素材",
                        wait.timedOut() || wait.failedItems() > 0 ? "warning" : "done");
                addAdmissionStage(result, wait);
                if ("autonomous".equalsIgnoreCase(params.getAutonomyMode())
                        && !gapService.analyze(req.getProjectId(), params).isSufficient()) {
                    autonomousRetry(task, result, req, params, keyword, missingVisualRoles, autoSources);
                }
            } else if ("autonomous".equalsIgnoreCase(params.getAutonomyMode())) {
                autonomousRetry(task, result, req, params, keyword, missingVisualRoles, autoSources);
            } else {
                updateStage(result, "补齐公开素材", "未找到可用公开素材，继续使用本地可读素材", "warning");
            }
        } else {
            addStage(result, "补齐公开素材", "本地素材充足，本次不发起外部公开素材抓取", "skipped");
        }
        persist(task, result);

        int analysesQueued = materialAnalysisService.queueUnanalysedAuthorizedVisuals(params, 24);
        if (analysesQueued > 0) {
            addStage(result, "镜头分析", "已为当前授权范围排队 " + analysesQueued
                    + " 条视频的场景分析；分析完成后会优先使用完整镜头，当前干跑会标明未分析回退", "working");
        } else {
            addStage(result, "镜头分析", "当前授权范围没有需要新建分析的可读视频素材", "skipped");
        }
        MaterialGapService.MaterialGapResult refreshed = gapService.analyze(req.getProjectId(), params);
        result.setFinalGap(refreshed);
        result.setReady(refreshed.isSufficient());
        addStage(result, "重新预检", refreshed.isSufficient()
                ? "素材池已更新，可继续执行出片干跑"
                : "素材仍未完全满足目标，干跑会使用当前已成功入库的素材", refreshed.isSufficient() ? "done" : "warning");
        if (isCancelled(task)) return snapshot(task);
        result.setStatus(result.isTimedOut() ? PreparationTask.STATUS_TIMEDOUT : PreparationTask.STATUS_DONE);
        persist(task, result);
        return result;
    }

    private Long autoFillFolderId(MixParams params) {
        if (Boolean.TRUE.equals(params.getStrictFolderSequence()) && params.getFolderReadSteps() != null) {
            return params.getFolderReadSteps().stream()
                    .filter(step -> step != null && !Boolean.FALSE.equals(step.getEnabled()) && step.getFolderId() != null)
                    .map(MixParams.FolderReadStep::getFolderId).findFirst().orElse(null);
        }
        return params.getFolderIds() != null && params.getFolderIds().size() == 1 ? params.getFolderIds().get(0) : null;
    }

    /** Runs one bounded second attempt against the same approved sources; it never expands source authority. */
    private void autonomousRetry(PreparationTask task, PrepareResult result, PrepareRequest req, MixParams params,
                                 String keyword, List<String> roles, List<String> sources) {
        addStage(result, "自主恢复", "首轮公开素材未满足缺口，开始一次受控第二轮检索与失败任务恢复", "working");
        for (Long crawlId : result.getCrawlJobIds()) {
            try { crawlJobService.retryFailed(crawlId); } catch (IllegalArgumentException ignored) {
                // A running or fully successful job has no safe retry candidate.
            }
        }
        MaterialGapService.AutoFillRequest retry = new MaterialGapService.AutoFillRequest();
        retry.setProjectId(req.getProjectId());
        retry.setParams(params);
        retry.setKeyword(keyword);
        retry.setSources(sources);
        retry.setPerSource(3);
        retry.setForce(true);
        retry.setRoles(roles);
        retry.setFolderId(autoFillFolderId(params));
        MaterialGapService.AutoFillResult second = gapService.autoFill(retry);
        if (!second.isAny()) {
            updateStage(result, "自主恢复", "已完成合规来源第二轮检索，仍无可入库素材；将保留证据并等待人工补充授权素材", "warning");
            return;
        }
        List<Long> allIds = new ArrayList<>(result.getCrawlJobIds());
        allIds.addAll(second.getCrawlJobIds());
        result.setCrawlJobIds(allIds);
        int limit = Math.max(10, Math.min(30, boundWait(req.getWaitSeconds()) / 2));
        WaitResult wait = waitForCrawlJobs(second.getCrawlJobIds(), limit, task, result);
        result.setTimedOut(result.isTimedOut() || wait.timedOut());
        result.setWaitedSeconds(result.getWaitedSeconds() + wait.waitedSeconds());
        addAdmissionStage(result, wait);
        updateStage(result, "自主恢复", wait.timedOut()
                ? "第二轮素材仍在安全入库；当前只会使用已通过准入的素材"
                : "第二轮公开素材任务已结束，正在重新预检", wait.timedOut() ? "warning" : "done");
    }

    /**
     * Polls the existing crawl queue at 1s intervals, bounded by {@code limitSeconds}.
     * Missing records count as finished so a deleted crawl job cannot make the loop
     * wait the full bound. Each heartbeat is persisted so GET polling exposes the
     * elapsed wait state instead of only the terminal result.
     */
    private WaitResult waitForCrawlJobs(List<Long> ids, int limitSeconds, PreparationTask task, PrepareResult result) {
        if (ids == null || ids.isEmpty()) return new WaitResult(0, false, "没有可等待的公开素材任务", 0, 0, List.of());
        long started = System.nanoTime();
        while (true) {
            List<CrawlJob> jobs = ids.stream().map(id -> crawlJobRepo.findById(id).orElse(null)).toList();
            CrawlOutcome outcome = crawlOutcome(jobs);
            // 记录不存在的任务视为已结束,避免 jobs.size()!=ids.size() 导致永远等满超时
            boolean complete = jobs.stream().allMatch(job -> job == null || isTerminal(job.getStatus()));
            int elapsed = (int) ((System.nanoTime() - started) / 1_000_000_000L);
            if (isCancelled(task)) return new WaitResult(elapsed, true, "准备已取消", outcome.admittedItems(), outcome.failedItems(), outcome.reasons());
            if (complete) {
                long done = jobs.stream().filter(java.util.Objects::nonNull)
                        .filter(job -> JobStatus.done.name().equals(job.getStatus())).count();
                return new WaitResult(elapsed, false, "公开素材任务已结束：" + done + "/" + ids.size() + " 个任务完成",
                        outcome.admittedItems(), outcome.failedItems(), outcome.reasons());
            }
            if (elapsed >= limitSeconds) {
                return new WaitResult(elapsed, true, "公开素材仍在入库，已等待 " + elapsed + " 秒；本次继续使用已成功入库的素材",
                        outcome.admittedItems(), outcome.failedItems(), outcome.reasons());
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WaitResult(elapsed, true, "等待公开素材被中断，本次继续使用已成功入库的素材",
                        outcome.admittedItems(), outcome.failedItems(), outcome.reasons());
            }
            // 心跳落盘：轮询接口可以看到等待中的进度，而不是只有终态
            result.setWaitedSeconds(elapsed + 1);
            updateStage(result, "等待公开素材", "已等待 " + (elapsed + 1) + " 秒，公开素材仍在入库（上限 " + limitSeconds + " 秒）", "working");
            persist(task, result);
        }
    }

    private CrawlOutcome crawlOutcome(List<CrawlJob> jobs) {
        int admitted = 0;
        int failed = 0;
        List<String> reasons = new ArrayList<>();
        for (CrawlJob job : jobs) {
            if (job == null || job.getId() == null) continue;
            for (CrawlTask crawlTask : crawlTaskRepo.findByJobIdOrderByIdxAsc(job.getId())) {
                String message = limit(crawlTask.getMessage(), 220);
                boolean rejected = message.startsWith("已下载但未通过质量准入：");
                if (JobStatus.failed.name().equals(crawlTask.getStatus()) || rejected) {
                    failed++;
                    if (!message.isBlank() && reasons.size() < 3) reasons.add(message);
                } else if (JobStatus.done.name().equals(crawlTask.getStatus())) {
                    admitted++;
                }
            }
        }
        return new CrawlOutcome(admitted, failed, List.copyOf(reasons));
    }

    private void addAdmissionStage(PrepareResult result, WaitResult wait) {
        String message;
        String status;
        if (wait.timedOut() && wait.admittedItems() == 0 && wait.failedItems() == 0) {
            message = "公开素材仍在后台下载，当前尚无可确认的入库结果；可在素材抓取页查看任务详情";
            status = "warning";
        } else if (wait.failedItems() > 0) {
            String details = wait.reasons().isEmpty() ? "请在素材抓取页查看任务详情" : String.join("；", wait.reasons());
            message = "成功入库 " + wait.admittedItems() + " 条，未通过或失败 " + wait.failedItems() + " 条：" + details;
            status = "warning";
        } else {
            message = "成功入库 " + wait.admittedItems() + " 条，所有已结束素材均通过媒体准入";
            status = "done";
        }
        addStage(result, "检查入库结果", message, status);
    }

    private String aiKeyword(Project project, String fallback) {
        String system = "你是短视频素材检索助手。只返回 2 到 8 个适合公开许可素材站点搜索的简短关键词，用空格分隔。"
                + "不要输出 URL、来源站点、命令、下载说明、营销承诺或任何解释。";
        String user = "品类：" + safe(project.getCategory())
                + "\n产品：" + safe(project.getProduct())
                + "\n品牌：" + safe(project.getBrand())
                + "\n卖点：" + limit(project.getSellingPoints(), 240)
                + "\n受众：" + safe(project.getAudience())
                + "\n已有检索词：" + safe(fallback);
        try {
            AiService.Answer answer = aiService.ask(UseCase.general, system, user, 0.2, 80, project.getRouteOverrides());
            if (!answer.ok()) return "";
            return sanitizeKeyword(answer.text(), project.getBannedWords());
        } catch (Exception e) {
            log.debug("project material keyword refinement failed: {}", e.toString());
            return "";
        }
    }

    private boolean isCancelled(PreparationTask task) {
        return task != null && PreparationTask.STATUS_CANCELLED.equals(task.getStatus());
    }

    private void persist(PreparationTask task, PrepareResult result) {
        if (isCancelled(task)) return;
        task.setStatus(result.getStatus());
        task.setKeyword(result.getKeyword());
        task.setAiUsed(result.isAiUsed());
        task.setReady(result.isReady());
        task.setTimedOut(result.isTimedOut());
        task.setWaitedSeconds(result.getWaitedSeconds());
        task.setCrawlJobIds(writeJson(result.getCrawlJobIds()));
        task.setStages(writeJson(result.getStages()));
        task.setInitialGap(writeJson(result.getInitialGap()));
        task.setFinalGap(writeJson(result.getFinalGap()));
        task.setAutoFill(writeJson(result.getAutoFill()));
        task.setLastActivityAt(LocalDateTime.now());
        taskRepo.save(task);
    }

    private void markFailed(Long taskId, String error) {
        try {
            taskRepo.findById(taskId).ifPresent(task -> {
                task.setStatus(PreparationTask.STATUS_FAILED);
                task.setError(limit(error, 2000));
                task.setLastActivityAt(LocalDateTime.now());
                taskRepo.save(task);
            });
        } catch (Exception e) {
            log.warn("无法落盘准备任务失败状态 {}: {}", taskId, e.toString());
        }
    }

    private PrepareResult snapshot(PreparationTask task) {
        PrepareResult result = new PrepareResult();
        result.setId(task.getId());
        result.setStatus(task.getStatus());
        result.setElapsedSec(elapsed(task));
        result.setAiUsed(Boolean.TRUE.equals(task.getAiUsed()));
        result.setKeyword(task.getKeyword());
        result.setReady(Boolean.TRUE.equals(task.getReady()));
        result.setTimedOut(Boolean.TRUE.equals(task.getTimedOut()));
        result.setWaitedSeconds(task.getWaitedSeconds() == null ? 0 : task.getWaitedSeconds());
        result.setCrawlJobIds(readJson(task.getCrawlJobIds(), new TypeReference<List<Long>>() {}, List.of()));
        result.setStages(readJson(task.getStages(), new TypeReference<List<Map<String, Object>>>() {}, new ArrayList<>()));
        result.setInitialGap(readJson(task.getInitialGap(), MaterialGapService.MaterialGapResult.class, null));
        result.setFinalGap(readJson(task.getFinalGap(), MaterialGapService.MaterialGapResult.class, null));
        result.setAutoFill(readJson(task.getAutoFill(), MaterialGapService.AutoFillResult.class, null));
        result.setMessage(messageFor(task));
        return result;
    }

    private String messageFor(PreparationTask task) {
        if (PreparationTask.STATUS_DONE.equals(task.getStatus())) return "出片准备完成";
        if (PreparationTask.STATUS_TIMEDOUT.equals(task.getStatus())) return "公开素材等待超时，本次使用已成功入库的素材继续预检";
        if (PreparationTask.STATUS_CANCELLED.equals(task.getStatus())) return "准备已取消；已发起的公开素材任务不会被连带取消";
        if (PreparationTask.STATUS_FAILED.equals(task.getStatus())) {
            return task.getError() == null ? "出片准备失败" : "出片准备失败：" + task.getError();
        }
        return "准备任务已提交，正在后台执行";
    }

    private long elapsed(PreparationTask task) {
        if (task.getCreatedAt() == null) return 0;
        return Duration.between(task.getCreatedAt(), LocalDateTime.now()).getSeconds();
    }

    private int boundWait(Integer waitSeconds) {
        if (waitSeconds == null) return DEFAULT_WAIT_SECONDS;
        return Math.max(0, Math.min(MAX_WAIT_SECONDS, waitSeconds));
    }

    /** Only enable Pexels after a user-supplied official API key has been configured. */
    private List<String> autoFillSources() {
        if (props.getPexelsApiKey() == null || props.getPexelsApiKey().isBlank()) return PUBLIC_SOURCES;
        List<String> sources = new ArrayList<>(PUBLIC_SOURCES);
        sources.add("pexels");
        return List.copyOf(sources);
    }

    private String sourceDescription(List<String> sources) {
        if (sources.contains("pexels")) {
            return "Wikimedia Commons、Internet Archive 与已授权的 Pexels 官方 API（仅使用合规公开素材）";
        }
        return "Wikimedia Commons、Internet Archive 的固定公开来源（CC0/公有领域/CC BY 白名单；Pexels 需先配置 APP_PEXELS_API_KEY）";
    }

    private boolean isTerminal(String status) {
        return JobStatus.done.name().equals(status) || JobStatus.failed.name().equals(status) || JobStatus.cancelled.name().equals(status);
    }

    private void addStage(PrepareResult result, String name, String message, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("message", message);
        row.put("status", status);
        result.getStages().add(row);
    }

    private void updateStage(PrepareResult result, String name, String message, String status) {
        for (Map<String, Object> stage : result.getStages()) {
            if (name.equals(stage.get("name"))) {
                stage.put("message", message);
                stage.put("status", status);
                return;
            }
        }
    }

    private String sanitizeKeyword(String value, String bannedWords) {
        String text = value == null ? "" : value.replaceAll("[^\\p{IsHan}A-Za-z0-9 _-]", " ")
                .replaceAll("\\s+", " ").trim();
        if (text.isBlank()) return "";
        Set<String> banned = new java.util.HashSet<>();
        if (bannedWords != null) {
            for (String token : bannedWords.toLowerCase(Locale.ROOT).split("[\\s,，、;；]+")) {
                if (!token.isBlank()) banned.add(token);
            }
        }
        List<String> kept = new ArrayList<>();
        for (String token : text.split(" ")) {
            if (!banned.contains(token.toLowerCase(Locale.ROOT))) kept.add(token);
            if (kept.size() == 8) break;
        }
        return String.join(" ", kept).substring(0, Math.min(120, String.join(" ", kept).length())).trim();
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T readJson(String json, Class<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return om.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return om.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String safe(String value) { return limit(value, 120); }
    private String limit(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
    private String concise(Exception e) {
        String s = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private record CrawlOutcome(int admittedItems, int failedItems, List<String> reasons) { }
    private record WaitResult(int waitedSeconds, boolean timedOut, String message,
                              int admittedItems, int failedItems, List<String> reasons) { }
}
