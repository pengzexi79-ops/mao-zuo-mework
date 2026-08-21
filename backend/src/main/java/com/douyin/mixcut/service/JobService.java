package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.*;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * 批量出片任务编排。
 *
 * <p>任务记录先落库，渲染只在事务提交后派发。运行中的 id 仅保存在本进程，
 * 重启后由 {@link #recoverInterruptedJobs()} 根据落库状态重新入队。每个 idx 的成功
 * 输出是恢复检查点，重跑时绝不会覆盖或重复渲染已有成功 idx。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private static final int ERROR_LIMIT = 4000;

    private final JobRepo jobRepo;
    private final JobOutputRepo outputRepo;
    private final WorkflowRepo workflowRepo;
    private final ProjectRepo projectRepo;
    private final MaterialFolderRepo folderRepo;
    private final SkillEngine skillEngine;
    private final RenderService renderService;
    private final CopyService copyService;
    private final NarrationService narrationService;
    private final MaterialDiagnosisService materialDiagnosisService;
    private final MaterialStore materialStore;
    /** 内容感知 OCR 用共享线程 + 超时，避免出片线程被素材识别阻塞（用户反馈"生成视频卡住"）。 */
    private static final java.util.concurrent.ExecutorService SUMMARY_OCR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "summary-ocr");
                t.setDaemon(true);
                return t;
            });
    private final EditorialBriefService editorialBriefService;
    private final DeliveryRepairService deliveryRepairService;
    private final OutputVersionRepo outputVersionRepo;
    private final OutputRepairRepo outputRepairRepo;
    private final AppProps props;
    private final ObjectMapper om = new ObjectMapper();

    @Qualifier("renderExecutor")
    private final Executor renderExecutor;

    /** 运行中任务的实时步骤描述（不落库，避免高频写库）。 */
    private final Map<Long, String> liveStep = new ConcurrentHashMap<>();
    private final Map<Long, Integer> livePhaseProgress = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    /** 当前 JVM 已派发或正在执行的任务，避免 afterCommit / 恢复 / watchdog 重复派发。 */
    private final Set<Long> dispatched = ConcurrentHashMap.newKeySet();

    // ---------------- 提交与派发 ----------------

    /** 新建 pending 任务并在事务提交后派发。线程池饱和时保持 pending，由 watchdog 安全重试。 */
    @Transactional
    public Job submit(Long workflowId, Long projectId, int count, String paramsJson, String name,
                      Integer timeoutSec, Integer staleAfterSec) {
        Job job = new Job();
        job.setWorkflowId(workflowId);
        job.setProjectId(projectId);
        job.setCount(Math.max(1, Math.min(200, count)));
        job.setTotal(job.getCount());
        job.setCurrent(0);
        job.setProgress(0);
        job.setStatus(JobStatus.pending.name());
        job.setName(name == null || name.isBlank()
                ? "批量出片 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                : name);
        job.setParams(freezeSubmission(workflowId, projectId, paramsJson));
        job.setTimeoutSec(normalizeOptionalSeconds(timeoutSec));
        job.setStaleAfterSec(normalizeOptionalSeconds(staleAfterSec));
        job.setLastActivityAt(LocalDateTime.now());
        Job saved = jobRepo.save(job);
        Long id = saved.getId();

        // 必须在提交后派发；否则异步线程可能读不到尚未提交的 job 行。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(id);
                }
            });
        } else {
            dispatch(id);
        }
        return saved;
    }

    /** 兼容服务层已有调用。 */
    public Job submit(Long workflowId, Long projectId, int count, String paramsJson, String name) {
        return submit(workflowId, projectId, count, paramsJson, name, null, null);
    }

    /**
     * 启动恢复入口。pending 直接重新派发；超过其无活动时限的 running 任务先回到 pending。
     * JobOutput.idx 是唯一检查点，恢复执行会跳过已有输出。重复调用只会尝试派发尚未在本 JVM
     * 执行的任务，因此是幂等的。
     */
    @Transactional
    public void recoverInterruptedJobs() {
        List<Job> pending = jobRepo.findByStatusOrderByIdAsc(JobStatus.pending.name());
        List<Job> staleRunning = new ArrayList<>();
        List<Long> toDispatch = new ArrayList<>();

        for (Job job : pending) toDispatch.add(job.getId());
        LocalDateTime now = LocalDateTime.now();
        for (Job job : jobRepo.findByStatusOrderByIdAsc(JobStatus.running.name())) {
            if (!isStale(job, now)) continue;
            try {
                int total = totalFor(job);
                int completed = successfulIndexes(job.getId(), total).size();
                job.setStatus(JobStatus.pending.name());
                job.setCurrent(completed);
                job.setProgress(progress(completed, total));
                job.setSummary("恢复排队：将跳过已成功的 " + completed + " 条");
                job.setError(null);
                job.setLastActivityAt(now);
                jobRepo.save(job);
                staleRunning.add(job);
                toDispatch.add(job.getId());
            } catch (Exception e) {
                markFailed(job, "服务启动恢复失败: " + concise(e));
            }
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    toDispatch.forEach(JobService.this::dispatch);
                }
            });
        } else {
            toDispatch.forEach(this::dispatch);
        }
        log.info("job recovery scheduled: pending={}, staleRunning={}", pending.size(), staleRunning.size());
    }

    /** 保证手动恢复、数据库短暂故障等场景遗留的 pending 任务最终会再次派发。 */
    @Scheduled(fixedDelayString = "${app.job-watchdog-delay-ms:30000}")
    public void retryPendingJobs() {
        try {
            for (Job job : jobRepo.findByStatusOrderByIdAsc(JobStatus.pending.name())) {
                dispatch(job.getId());
            }
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Setup mode: database credentials are not ready yet. The environment center remains available.
        }
    }

    /**
     * Watchdog 不尝试中断 ffmpeg（跨平台且不能保证安全）。当前 JVM 仍在执行的任务由渲染回调持续
     * 心跳，watchdog 只处理确实失去执行者的 stale 记录，避免慢机器在长拼接阶段被误判失败。
     */
    @Scheduled(fixedDelayString = "${app.job-watchdog-delay-ms:30000}")
    @Transactional
    public void markStaleJobs() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Job job : jobRepo.findByStatusOrderByIdAsc(JobStatus.running.name())) {
                LocalDateTime activity = activityAt(job, now);
                long inactiveSec = Duration.between(activity, now).getSeconds();
                long totalSec = Duration.between(job.getCreatedAt() == null ? activity : job.getCreatedAt(), now).getSeconds();
                int staleLimit = configuredStaleAfter(job);
                int timeoutLimit = configuredTimeout(job);
                if (inactiveSec >= staleLimit && !dispatched.contains(job.getId())) {
                    markFailed(job, "任务无活动超过 " + staleLimit + " 秒，已由 watchdog 标记为失败；请确认素材与 FFmpeg 环境后重新提交。");
                    liveStep.remove(job.getId());
                } else if (totalSec >= timeoutLimit && !dispatched.contains(job.getId())) {
                    markFailed(job, "任务执行超过 " + timeoutLimit + " 秒，已由 watchdog 标记为失败；为避免破坏正在写入的成片，未强行中断外部渲染进程。");
                    liveStep.remove(job.getId());
                }
            }
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Setup mode: no task state can be observed until MySQL credentials are configured.
        }
    }

    /**
     * 派发以 dispatched 集合实现进程内幂等。renderExecutor 采用 AbortPolicy：执行器饱和时
     * 抛出 RejectedExecutionException，本方法捕获后保留任务为 pending，由看门狗
     * （app.job-watchdog-delay-ms，默认 30s）重试派发，不会遗留永远 pending 的任务。
     * 注意：CallerRunsPolicy 仅用于 crawl 执行器，renderExecutor 不使用它。
     */
    private void dispatch(Long jobId) {
        if (jobId == null || !dispatched.add(jobId)) return;
        try {
            renderExecutor.execute(() -> {
                try {
                    safeRun(jobId);
                } finally {
                    dispatched.remove(jobId);
                }
            });
        } catch (RuntimeException e) {
            dispatched.remove(jobId);
            recordPendingDispatchFailure(jobId, "渲染派发失败: " + concise(e));
            log.warn("job {} dispatch failed", jobId, e);
        }
    }

    private void recordPendingDispatchFailure(Long jobId, String message) {
        try {
            jobRepo.findById(jobId).ifPresent(job -> {
                if (JobStatus.pending.name().equals(job.getStatus())) {
                    job.setSummary(message);
                    job.setLastActivityAt(LocalDateTime.now());
                    jobRepo.save(job);
                }
            });
        } catch (Exception e) {
            log.error("cannot record dispatch failure for job {}", jobId, e);
        }
    }

    // ---------------- 执行 ----------------

    /** 兜底：未捕获异常必须落库，不能让任务永久停在 running。 */
    void safeRun(Long jobId) {
        try {
            runJob(jobId);
        } catch (Throwable t) {
            log.error("job {} crashed", jobId, t);
            try {
                jobRepo.findById(jobId).ifPresent(job -> markFailed(job, "任务异常终止: " + concise(t)));
            } catch (Exception ignore) {
                // 状态落库也失败时只能靠日志。
            }
        } finally {
            liveStep.remove(jobId);
            livePhaseProgress.remove(jobId);
            cancelled.remove(jobId);
        }
    }

    public void cancel(Long jobId) {
        jobRepo.findById(jobId).ifPresent(job -> {
            if (!isTerminal(job.getStatus())) {
                cancelled.add(jobId);
                boolean wasPending = JobStatus.pending.name().equals(job.getStatus());
                int completed = successfulIndexes(jobId, totalFor(job)).size();
                job.setStatus(JobStatus.cancelled.name());
                job.setCurrent(completed);
                job.setProgress(progress(completed, totalFor(job)));
                job.setSummary(wasPending
                        ? "已取消，尚未开始渲染" : "已取消；当前外部渲染步骤结束后不会继续下一条");
                job.setError(null);
                job.setLastActivityAt(LocalDateTime.now());
                jobRepo.save(job);
                liveStep.remove(jobId);
            }
        });
    }

    public void pause(Long jobId) {
        jobRepo.findById(jobId).ifPresent(job -> {
            if (isTerminal(job.getStatus())) throw new IllegalArgumentException("已结束的任务不能暂停");
            int completed = successfulIndexes(jobId, Integer.MAX_VALUE).size();
            job.setStatus(JobStatus.paused.name());
            job.setCurrent(completed);
            job.setProgress(progress(completed, totalFor(job)));
            job.setSummary("正在暂停：当前条完成后会保留成片；已完成 " + completed + " 条");
            job.setError(null);
            heartbeat(job, "暂停请求已收到");
            jobRepo.save(job);
        });
    }

    public void resume(Long jobId) {
        jobRepo.findById(jobId).ifPresent(job -> {
            if (!JobStatus.paused.name().equals(job.getStatus()) && !JobStatus.awaiting_decision.name().equals(job.getStatus())) {
                throw new IllegalArgumentException("只有已暂停或等待人工决策的任务可以继续");
            }
            String appliedAction = JobStatus.awaiting_decision.name().equals(job.getStatus())
                    ? applyRecommendedRepair(job) : null;
            job.setStatus(JobStatus.pending.name());
            job.setSummary(appliedAction == null
                    ? "继续排队：将从下一条未完成成片开始"
                    : "已应用推荐修复「" + appliedAction + "」，将从下一条未完成成片开始");
            job.setError(null);
            heartbeat(job, appliedAction == null ? "继续排队" : "已应用推荐修复");
            jobRepo.save(job);
            dispatch(jobId);
        });
    }

    /**
     * Continue only with a recommendation that is safe without more user input. Replacing BGM
     * remains a deliberate manual decision because silently selecting an arbitrary track would
     * recreate the audio mismatch the repair flow is meant to avoid.
     */
    private String applyRecommendedRepair(Job job) {
        OutputRepair repair = outputRepairRepo.findByJobIdAndIdxOrderByIdAsc(job.getId(),
                        Math.max(1, (job.getCurrent() == null ? 0 : job.getCurrent()) + 1)).stream()
                .filter(item -> "awaiting_decision".equals(item.getStatus()) || "proposed".equals(item.getStatus()))
                .reduce((first, second) -> second).orElse(null);
        if (repair == null) return null;
        String action = repair.getRecommendedAction();
        if (!Set.of("keep-original-audio", "relax-subtitle", "regenerate-plan", "switch-hook", "retry-auto").contains(action)) {
            return null;
        }
        Project project = job.getProjectId() == null ? null : projectRepo.findById(job.getProjectId()).orElse(null);
        MixParams params = mergeProjectDefaults(job.getParams(), project);
        if ("keep-original-audio".equals(action)) params.setAudioMode("original");
        else if ("relax-subtitle".equals(action)) {
            params.setAutoSubtitles(false);
            params.setBurnAiVoiceCaptions(false);
        } else if ("switch-hook".equals(action)) {
            params.setHookStrategy(null);
            params.setAutoRehook(false);
        } else if ("regenerate-plan".equals(action) || "retry-auto".equals(action)) {
            params.setSeed(System.nanoTime());
        }
        try {
            job.setParams(updateFrozenParams(job.getParams(), params.normalized()));
        } catch (Exception e) {
            log.warn("cannot apply recommended repair for job {}: {}", job.getId(), e.toString());
            return null;
        }
        repair.setSelectedAction(action);
        repair.setStatus("approved_manual");
        repair.setExecutionImpact("继续任务时自动应用已记录的安全修复动作");
        outputRepairRepo.save(repair);
        return action;
    }

    /** 对全部失败/质检拦截项重新排队；已通过的 idx 继续作为检查点，不会重渲染。 */
    public void retryFailedItems(Long jobId) {
        jobRepo.findById(jobId).ifPresent(job -> {
            if (JobStatus.pending.name().equals(job.getStatus()) || JobStatus.running.name().equals(job.getStatus())) {
                throw new IllegalArgumentException("任务正在执行，不能同时发起修复重试");
            }
            int completed = successfulIndexes(jobId, totalFor(job)).size();
            if (completed >= totalFor(job) && !JobStatus.awaiting_decision.name().equals(job.getStatus())) {
                throw new IllegalArgumentException("任务没有待修复的失败项");
            }
            job.setStatus(JobStatus.pending.name());
            job.setCurrent(completed);
            job.setProgress(progress(completed, totalFor(job)));
            job.setSummary("修复重试已排队：将保留 " + completed + " 条已通过成片");
            job.setError(null);
            heartbeat(job, "修复重试排队");
            jobRepo.save(job);
            dispatch(jobId);
        });
    }

    public String currentStep(Long jobId) {
        String live = liveStep.get(jobId);
        if (live != null) return live;
        return jobRepo.findById(jobId).map(job -> job.getCurrentStep() == null ? "" : job.getCurrentStep()).orElse("");
    }

    public int currentPhaseProgress(Long jobId) {
        Integer live = livePhaseProgress.get(jobId);
        if (live != null) return live;
        return jobRepo.findById(jobId).map(job -> job.getPhaseProgress() == null ? 0 : job.getPhaseProgress()).orElse(0);
    }

    void runJob(Long jobId) {
        Job job = reloadOrNull(jobId);
        if (job == null || isTerminal(job.getStatus()) || JobStatus.paused.name().equals(job.getStatus())) return;

        int total = totalFor(job);
        boolean continuous = isContinuous(job);
        Set<Integer> successful = successfulIndexes(jobId, continuous ? Integer.MAX_VALUE : total);
        Set<String> usedSegments = loadUsedSegments(jobId);
        Map<String, Integer> batchReuseUsage = new LinkedHashMap<>();
        Long projectId = job.getProjectId();
        Set<String> projectFuzzyKeys = projectId != null
                ? loadProjectUsedFuzzyKeys(projectId, jobId) : new HashSet<>();
        if (JobStatus.cancelled.name().equals(job.getStatus()) || cancelled.contains(jobId)) {
            cancelPersisted(job, successful.size());
            return;
        }
        int ok = successful.size();
        int fail = 0;
        List<String> warnings = new ArrayList<>();

        job.setStatus(JobStatus.running.name());
        job.setCurrent(ok);
        job.setProgress(progress(ok, total));
        job.setSummary(ok == 0 ? "开始渲染" : "恢复渲染：跳过已成功的 " + ok + " 条");
        heartbeat(job, "恢复准备中");
        jobRepo.save(job);

        Project project = job.getProjectId() == null ? null : projectRepo.findById(job.getProjectId()).orElse(null);
        MixParams params = mergeProjectDefaults(job.getParams(), project);
        if (params.getSeed() == null) {
            params.setSeed(stableSeed(job.getWorkflowId(), job.getProjectId(), job.getParams()));
            try {
                job.setParams(updateFrozenParams(job.getParams(), params.normalized()));
                jobRepo.save(job);
            } catch (Exception e) {
                log.warn("cannot persist deterministic job seed {}: {}", jobId, e.toString());
            }
        }
        params.setRecentAudioUsage(new LinkedHashMap<>(loadProjectRecentAudioUsage(job.getProjectId(), jobId)));
        String def = resolveWorkflow(job);
        if (job.getProjectId() != null) {
            editorialBriefService.persistForJob(jobId, project);
        }
        if (continuous) {
            runContinuous(jobId, job, project, params, def, successful, usedSegments, batchReuseUsage);
            return;
        }

        // 只为未完成 idx 请求钩子，恢复时避免多余的 AI 请求；idx 仍使用原始下标保证差异化稳定。
        List<Integer> remaining = new ArrayList<>();
        for (int idx = 1; idx <= total; idx++) if (!successful.contains(idx)) remaining.add(idx);
        List<String> hooks = createHooks(project, params, remaining.size(), remaining.isEmpty() ? 0 : remaining.get(0) - 1,
                buildHookEvidence(project, params), warnings);

        int hookPosition = 0;
        for (int idx = 1; idx <= total; idx++) {
            if (successful.contains(idx)) continue;
            Job latest = reloadOrNull(jobId);
            if (latest == null || isTerminal(latest.getStatus()) || JobStatus.paused.name().equals(latest.getStatus()) || cancelled.contains(jobId)) {
                if (latest != null && (cancelled.contains(jobId) || JobStatus.cancelled.name().equals(latest.getStatus()))) {
                    cancelPersisted(latest, ok);
                }
                liveStep.remove(jobId);
                cancelled.remove(jobId);
                return;
            }
            if (exceededJobTimeout(latest)) {
                markFailed(latest, "任务执行超过 " + configuredTimeout(latest) + " 秒，停止继续派发新的成片");
                liveStep.remove(jobId);
                return;
            }

            liveStep.put(jobId, "第 " + idx + "/" + total + " 条：准备中");
            heartbeat(latest, "第 " + idx + "/" + total + " 条：准备中");
            jobRepo.save(latest);

            try {
                MixParams itemParams = copyParams(params);
                if (!hooks.isEmpty()) {
                    itemParams.setHookText(hooks.get(hookPosition++ % hooks.size()));
                    itemParams.setAutoGeneratedHook(true);
                }
                itemParams.setHookStrategy(HookStrategy.select(project, idx - 1).name());
                if (Boolean.TRUE.equals(itemParams.getAutoRehook())
                        && (itemParams.getRehookText() == null || itemParams.getRehookText().isBlank())) {
                    try {
                        itemParams.setRehookText(copyService.rehook(project, null,
                                HookStrategy.safeValueOf(itemParams.getHookStrategy())));
                    } catch (Exception e) {
                        warnings.add("第 " + idx + " 条中段再钩子生成失败: " + concise(e));
                    }
                }
                SkillEngine.Ctx ctx = new SkillEngine.Ctx();
                if (itemParams.getHookText() != null && !itemParams.getHookText().isBlank()) {
                    ctx.setHookText(itemParams.getHookText());
                }
                final int displayIdx = idx;
                RenderAttempt attempt = renderItemWithRetry(project, itemParams, def, idx - 1,
                        usedSegments, batchReuseUsage, projectFuzzyKeys, jobId, displayIdx, total, ctx, warnings, latest);
                MixPlanner.Plan plan = attempt.plan;
                RenderService.RenderResult result = attempt.result;
                if (attempt.awaitingDecision) {
                    saveQcBlockedOutput(jobId, idx, result, plan == null ? Set.of() : plan.segmentKeys(), attempt.retries,
                            plan == null ? null : plan.getHookStrategy(), buildDowngradeInfo(plan, result, attempt.retries),
                            buildUsedMaterials(plan));
                    Job waiting = reloadOrNull(jobId);
                    if (waiting != null) markAwaitingDecision(waiting, idx, result.getError());
                    liveStep.remove(jobId);
                    return;
                }
                if (!plan.isUsable() || missingRequiredAudio(plan) || insufficientAudioCoverage(plan)) {
                    boolean noAudio = missingRequiredAudio(plan);
                    boolean shortAudio = insufficientAudioCoverage(plan);
                    String reason = noAudio ? missingRequiredAudioMessage()
                            : (shortAudio ? insufficientAudioCoverageMessage(plan) : String.join("；", plan.getNotes()));
                    Job failed = reloadOrNull(jobId);
                    if (failed != null) {
                        String prefix = noAudio ? "批量出片已停止：当前计划没有可用音轨。"
                                : (shortAudio ? "批量出片已停止：当前口播无法覆盖全片。"
                                : "批量出片已停止：当前素材无法满足时长要求。");
                        markFailed(failed, prefix
                                + (reason.isBlank() ? "请补充可读素材或降低目标时长后重新干跑。" : reason));
                    }
                    liveStep.remove(jobId);
                    return;
                }
                Job afterRender = reloadOrNull(jobId);
                if (afterRender != null && (JobStatus.cancelled.name().equals(afterRender.getStatus())
                        || cancelled.contains(jobId))) {
                    cancelPersisted(afterRender, successful.size());
                    discardRenderResult(result);
                    return;
                }
                if (afterRender != null && JobStatus.failed.name().equals(afterRender.getStatus())) {
                    discardRenderResult(result);
                    liveStep.remove(jobId);
                    return;
                }
                if (result.isOk()) {
                    if (saveOutputIfAbsent(jobId, idx, result, plan.segmentKeys(), attempt.retries,
                            plan.getHookStrategy(), buildDowngradeInfo(plan, result, attempt.retries),
                            buildUsedMaterials(plan), result.getQcJson())) {
                        usedSegments.addAll(plan.segmentKeys());
                        recordBatchAudioUsage(params.getRecentAudioUsage(), plan, warnings, idx);
                        recordBatchReuse(batchReuseUsage, plan);
                        successful.add(idx);
                        ok++;
                    } else if (hasSuccessfulOutput(jobId, idx)) {
                        // 另一个恢复执行已成功写入同一 idx，按检查点继续而不是重复计数。
                        successful.add(idx);
                    }
                    if (!result.getWarnings().isEmpty()) {
                        warnings.add("第 " + idx + " 条: " + String.join("；", result.getWarnings()));
                    }
                } else {
                    fail++;
                    if ("fail".equals(result.getQcStatus())) {
                        saveQcBlockedOutput(jobId, idx, result, plan.segmentKeys(), attempt.retries,
                                plan.getHookStrategy(), buildDowngradeInfo(plan, result, attempt.retries),
                                buildUsedMaterials(plan));
                    }
                    warnings.add("第 " + idx + " 条渲染失败: " + result.getError());
                }
            } catch (Exception e) {
                fail++;
                warnings.add("第 " + idx + " 条异常: " + concise(e));
                log.error("job {} item {} failed", jobId, idx, e);
            }

            Job progressJob = reloadOrNull(jobId);
            if (progressJob == null || isTerminal(progressJob.getStatus()) || JobStatus.paused.name().equals(progressJob.getStatus())) {
                if (progressJob != null && JobStatus.cancelled.name().equals(progressJob.getStatus())) {
                    cancelPersisted(progressJob, successful.size());
                } else if (progressJob != null && JobStatus.paused.name().equals(progressJob.getStatus())) {
                    progressJob.setCurrent(successful.size());
                    progressJob.setProgress(progress(successful.size(), total));
                    progressJob.setSummary("已暂停；已完成 " + successful.size() + " 条，继续后从下一条开始");
                    heartbeat(progressJob, "已暂停");
                    jobRepo.save(progressJob);
                }
                liveStep.remove(jobId);
                return;
            }
            progressJob.setCurrent(successful.size());
            progressJob.setProgress(progress(successful.size(), total));
            heartbeat(progressJob, "第 " + idx + "/" + total + " 条完成");
            jobRepo.save(progressJob);
        }

        Job finished = reloadOrNull(jobId);
        if (finished == null || isTerminal(finished.getStatus())) {
            liveStep.remove(jobId);
            return;
        }
        int missing = total - successful.size();
        finished.setStatus(missing == 0 ? JobStatus.done.name()
                : (successful.isEmpty() ? JobStatus.failed.name() : JobStatus.done.name()));
        finished.setProgress(100);
        finished.setCurrent(successful.size());
        finished.setSummary("成功 " + successful.size() + " 条，失败 " + Math.max(fail, missing) + " 条");
        // qcStatus=warn is a delivered output that already has structured QC data on JobOutput.
        // Keep Job.error exclusively for unfinished or genuinely failed render items.
        finished.setError(missing == 0 ? null : limitWarnings(warnings));
        heartbeat(finished, "完成");
        jobRepo.save(finished);
        liveStep.remove(jobId);
        cancelled.remove(jobId);
        log.info("job {} finished: ok={} fail={}", jobId, successful.size(), Math.max(fail, missing));
    }

    /**
     * 成功输出是 job 的幂等检查点。唯一索引仍是最后一道并发保护；若另一执行者先写入，
     * 调用方会重新读取该 idx 并继续，不把重复键错误当成任务失败。
     */
    private boolean saveOutputIfAbsent(Long jobId, int idx, RenderService.RenderResult result, Set<String> segmentKeys,
                                       int retryCount, String hookStrategy, String downgradeInfo,
                                       String usedMaterials, String qcJson) {
        Optional<JobOutput> existing = outputRepo.findByJobIdAndIdx(jobId, idx);
        if (existing.isPresent()) {
            if (hasSuccessfulOutput(jobId, idx)) return false;
            // Replace an earlier diagnostics-only QC block if a later user-triggered attempt succeeds.
            outputRepo.delete(existing.get());
            outputRepo.flush();
        }
        JobOutput output = new JobOutput();
        output.setJobId(jobId);
        output.setIdx(idx);
        output.setFilePath(result.getFilePath());
        output.setDurationSec(result.getDurationSec());
        output.setThumbnail(result.getThumbnail());
        output.setQcStatus(result.getQcStatus() == null ? "pass" : result.getQcStatus());
        output.setQcReport(result.getQcReport());
        output.setQcJson(qcJson);
        output.setRetryCount(retryCount);
        output.setHookStrategy(hookStrategy);
        output.setDowngradeInfo(downgradeInfo);
        output.setUsedMaterials(usedMaterials);
        try {
            output.setSegmentKeys(om.writeValueAsString(segmentKeys == null ? Set.of() : segmentKeys));
        } catch (Exception e) {
            throw new IllegalStateException("无法保存成片去重切片键", e);
        }
        try {
            outputRepo.saveAndFlush(output);
            linkPassedVersion(jobId, idx, output);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // 跨 JVM 恢复时，赢家事务可能尚未对当前连接可见；短暂重查后再决定是否报告失败。
            for (int attempt = 0; attempt < 3; attempt++) {
                if (hasSuccessfulOutput(jobId, idx)) return false;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw duplicate;
        }
    }

    /** 最终交付指针只关联通过 QC 的版本；此前候选仍保留在版本历史中。 */
    private void linkPassedVersion(Long jobId, int idx, JobOutput output) {
        try {
            outputVersionRepo.findTopByJobIdAndIdxOrderByVersionNoDesc(jobId, idx).ifPresent(version -> {
                version.setJobOutputId(output.getId());
                version.setStatus("passed");
                outputVersionRepo.save(version);
            });
        } catch (Exception e) {
            log.warn("cannot link passed output version for job {} item {}: {}", jobId, idx, e.toString());
        }
    }

    /** Save a QC-blocked item for diagnostics only; it is not a delivery checkpoint or downloadable file. */
    private void saveQcBlockedOutput(Long jobId, int idx, RenderService.RenderResult result, Set<String> segmentKeys,
                                     int retryCount, String hookStrategy, String downgradeInfo, String usedMaterials) {
        Optional<JobOutput> existing = outputRepo.findByJobIdAndIdx(jobId, idx);
        if (existing.isPresent() && hasSuccessfulOutput(jobId, idx)) return;
        JobOutput output = existing.orElseGet(JobOutput::new);
        output.setJobId(jobId);
        output.setIdx(idx);
        // QC-blocked candidates stay in the controlled workspace. A public JobOutput only exposes diagnostics.
        output.setFilePath(null);
        output.setDurationSec(null);
        output.setThumbnail(null);
        output.setQcStatus("fail");
        output.setQcReport(result.getQcReport());
        output.setQcJson(result.getQcJson());
        output.setRetryCount(retryCount);
        output.setHookStrategy(hookStrategy);
        output.setDowngradeInfo(downgradeInfo);
        output.setUsedMaterials(usedMaterials);
        try {
            output.setSegmentKeys(om.writeValueAsString(segmentKeys == null ? Set.of() : segmentKeys));
            outputRepo.saveAndFlush(output);
        } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
            log.debug("QC-blocked output already recorded for job {} item {}", jobId, idx);
        } catch (Exception e) {
            log.warn("cannot persist QC diagnostics for job {} item {}: {}", jobId, idx, e.toString());
        }
    }

    /**
     * 生成计划并渲染，最多自动重试 {@link #MAX_OUTPUT_RETRIES} 次。只对可替换失败重试：
     * 每次重试切换钩子策略、清理按策略生成的中段再钩子，并按新 variant 重新切分素材
     * （连带重新选择候选素材与 BGM）。硬失败（媒体不可读 / 无音频 / 素材不足 / 未授权 / 超时）
     * 直接返回，不浪费算力。
     */
    private RenderAttempt renderItemWithRetry(Project project, MixParams itemParams, String def, int baseVariant,
                                              Set<String> usedSegments, Map<String, Integer> batchReuseUsage,
                                              Set<String> projectFuzzyKeys, Long jobId,
                                              int idx, int total, SkillEngine.Ctx ctx, List<String> warnings, Job job) {
        if ("ai-voice".equalsIgnoreCase(itemParams.getAudioMode()) && itemParams.getVoiceMaterialId() != null) {
            itemParams.setAudioMode("material-audio");
            ctx.log("已指定素材口播，按素材音轨模式执行，不生成 AI 配音");
        }
        int retries = 0;
        int repairLimit = Math.max(0, props.getMaxRepairIterations());
        RenderService.RenderResult result = new RenderService.RenderResult();
        MixPlanner.Plan plan = null;
        NarrationService.NarrationResult narration = null;
        String strategy = itemParams.getHookStrategy();
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(configuredTimeout(job));
        Set<String> attemptedStrategies = new HashSet<>();
        Set<String> seenFailureSignatures = new HashSet<>();
        OutputVersion priorVersion = null;
        boolean awaitingDecision = false;
        for (int attempt = 0; attempt <= repairLimit; attempt++) {
            if (java.time.Instant.now().isAfter(deadline)) {
                result.setError("修复过程超过本条任务时限，已停止并保留诊断");
                break;
            }
            int variant = baseVariant + attempt;
            if (attempt > 0) {
                retries = attempt;
                strategy = HookStrategy.select(project, variant).name();
                itemParams.setHookStrategy(strategy);
                // Only automatic hooks may change with a new visual plan. A user-authored hook remains authoritative.
                if (Boolean.TRUE.equals(itemParams.getAutoGeneratedHook())) {
                    itemParams.setHookText(null);
                    try {
                        itemParams.setHookText(copyService.hook(project, buildHookEvidence(project, itemParams),
                                HookStrategy.safeValueOf(strategy)));
                    } catch (Exception e) {
                        warnings.add("第 " + idx + " 条修复版本钩子生成失败：" + concise(e));
                    }
                }
                if (Boolean.TRUE.equals(itemParams.getAutoRehook())) itemParams.setRehookText(null);
                // The visual plan changes on each repair attempt. Reusing attempt-0 narration
                // would leave ASR cues and spoken product references attached to new footage.
                narration = null;
                attemptedStrategies.add(strategy);
                warnings.add("第 " + idx + " 条开始修复版本 " + (attempt + 1) + "：重新规划素材与钩子策略");
            }
            SkillEngine.Ctx useCtx = attempt == 0 ? ctx : freshCtx(itemParams, narration);
            plan = batchSafePlan(def, project, itemParams, variant, usedSegments, batchReuseUsage,
                    step -> updateStep(jobId, idx, total, step), useCtx, jobId, projectFuzzyKeys);
            refreshAutomaticHook(project, itemParams, plan, useCtx, warnings, idx);
            if (narration == null) {
                narration = prepareAiVoice(project, itemParams, useCtx, warnings, jobId, idx, plan.getPlannedSec(), plan);
            }
            if (narration != null) {
                plan.setVoiceMaterialId(narration.voice().getId());
                plan.setVoicePath(narration.voice().getFilePath());
                plan.setVoiceDurationSec(narration.voice().getDurationSec() == null ? 0 : narration.voice().getDurationSec());
            }
            attachNarration(plan, narration);
            OutputVersion version = freezeOutputPlan(jobId, idx, attempt + 1, plan, itemParams,
                    attempt == 0 ? "render" : "automatic-repair");
            if (!plan.isUsable() || missingRequiredAudio(plan) || insufficientAudioCoverage(plan)) {
                result.setError(!plan.isUsable() ? "剪辑计划未达到交付下限：" + String.join("；", plan.getNotes())
                        : (missingRequiredAudio(plan) ? missingRequiredAudioMessage() : insufficientAudioCoverageMessage(plan)));
            } else {
                String outName = String.format("%s_%d_%02d_v%d_%d", itemParams.getNamePrefix(), jobId, idx,
                        attempt + 1, System.currentTimeMillis() % 100000);
                result = renderService.render(plan, itemParams, outName,
                        step -> updateStep(jobId, idx, total, step), deadline);
            }
            finalizeOutputVersion(version, plan, result);
            if (result.isOk()) {
                markVersionPassed(version);
                markVersionSuperseded(priorVersion);
                completeRepairAfterQc(priorVersion, result.getQcJson(), true, null);
                completePendingManualRepairs(jobId, idx, result.getQcJson(), true, null);
                break;
            }
            DeliveryRepairService.RepairAssessment assessment = deliveryRepairService.assess(result, itemParams, plan, attempt);
            completePendingManualRepairs(jobId, idx, result.getQcJson(), false, assessment.getReason());
            recordRepair(jobId, idx, version, assessment, result.getQcJson());
            String failureSignature = repairFailureSignature(assessment, result);
            boolean repeatedFailure = !seenFailureSignatures.add(failureSignature);
            boolean sameStrategy = assessment.getRecommendedAction() != null
                    && attemptedStrategies.contains(assessment.getRecommendedAction());
            if (repeatedFailure || sameStrategy) {
                markVersionRolledBack(priorVersion, result.getQcJson());
                markVersionAwaitingDecision(version, assessment);
                completeRepairAfterQc(priorVersion, result.getQcJson(), false,
                        "修复后未改善，已停止重复策略");
                awaitingDecision = true;
                warnings.add("第 " + idx + " 条自动修复未改善，已保留候选并等待人工决策：" + assessment.getEvidence());
                break;
            }
            if (!assessment.isAutoFixable() || attempt >= repairLimit || !deliveryRepairService.applyAutomatic(itemParams, assessment)) {
                markVersionRolledBack(priorVersion, result.getQcJson());
                markVersionAwaitingDecision(version, assessment);
                completeRepairAfterQc(priorVersion, result.getQcJson(), false, assessment.getReason());
                awaitingDecision = true;
                warnings.add("第 " + idx + " 条等待人工决策：" + assessment.getEvidence());
                break;
            }
            attemptedStrategies.add(assessment.getRecommendedAction());
            markVersionRepairing(version, assessment);
            priorVersion = version;
            warnings.add("第 " + idx + " 条自动修复：" + assessment.getRecommendedAction());
        }
        return new RenderAttempt(plan, result, retries, strategy, awaitingDecision);
    }

    /** Persist the immutable render input before FFmpeg starts. */
    private OutputVersion freezeOutputPlan(Long jobId, int idx, int versionNo, MixPlanner.Plan plan,
                                           MixParams params, String strategy) {
        OutputVersion version = new OutputVersion();
        version.setJobId(jobId);
        version.setIdx(idx);
        int nextVersion = outputVersionRepo.findTopByJobIdAndIdxOrderByVersionNoDesc(jobId, idx)
                .map(previous -> Math.max(1, previous.getVersionNo() == null ? 1 : previous.getVersionNo() + 1))
                .orElse(Math.max(1, versionNo));
        version.setVersionNo(nextVersion);
        version.setStatus("plan_frozen");
        version.setUsedMaterials(buildUsedMaterials(plan));
        version.setRepairStrategy(strategy);
        if (nextVersion > 1) version.setParentVersionNo(nextVersion - 1);
        try {
            version.setPlanSnapshot(om.writeValueAsString(plan));
            version.setParamsSnapshot(om.writeValueAsString(params));
            return outputVersionRepo.saveAndFlush(version);
        } catch (Exception e) {
            log.warn("cannot freeze output plan for job {} item {}: {}", jobId, idx, e.toString());
            return version;
        }
    }

    /** Complete a frozen version with renderer and QC facts; this method never mutates its plan input. */
    private void finalizeOutputVersion(OutputVersion version, MixPlanner.Plan plan, RenderService.RenderResult result) {
        if (version == null) return;
        version.setStatus(result != null && result.isOk() ? "passed" : "qc_failed");
        version.setFilePath(result == null ? null : result.getFilePath());
        version.setDurationSec(result == null ? null : result.getDurationSec());
        version.setThumbnail(result == null ? null : result.getThumbnail());
        version.setUsedMaterials(buildUsedMaterials(plan));
        version.setQcJson(result == null ? null : result.getQcJson());
        version.setQcReport(result == null ? null : result.getQcReport());
        version.setError(result == null ? "未返回渲染结果" : result.getError());
        if (version.getId() != null) outputVersionRepo.save(version);
    }

    private void recordRepair(Long jobId, int idx, OutputVersion version,
                              DeliveryRepairService.RepairAssessment assessment, String beforeQc) {
        if (assessment == null) return;
        try {
            OutputRepair repair = new OutputRepair();
            repair.setOutputVersionId(version == null ? null : version.getId());
            repair.setJobId(jobId);
            repair.setIdx(idx);
            repair.setCategory(assessment.getCategory());
            repair.setSeverity(assessment.getSeverity());
            repair.setIssueId(assessment.getIssueId());
            repair.setEvidence(assessment.getEvidence());
            repair.setAutoFixable(assessment.isAutoFixable());
            repair.setAiAssessment(assessment.getAiAssessment());
            repair.setRecommendedAction(assessment.getRecommendedAction());
            repair.setCandidateActions(om.writeValueAsString(assessment.getCandidateActions()));
            repair.setSelectedAction(assessment.getRecommendedAction());
            repair.setExecutionImpact(assessment.getReason());
            repair.setStatus(assessment.isAutoFixable() ? "approved_auto" : "awaiting_decision");
            repair.setBeforeQc(beforeQc);
            outputRepairRepo.save(repair);
        } catch (Exception e) {
            log.warn("cannot persist output repair for job {} item {}: {}", jobId, idx, e.toString());
        }
    }

    private void markVersionPassed(OutputVersion version) {
        if (version == null || version.getId() == null) return;
        version.setStatus("passed");
        outputVersionRepo.save(version);
    }

    private void markVersionRepairing(OutputVersion version, DeliveryRepairService.RepairAssessment assessment) {
        if (version == null || version.getId() == null) return;
        version.setStatus("repairing");
        version.setRepairStrategy(assessment == null ? "automatic-repair" : assessment.getRecommendedAction());
        outputVersionRepo.save(version);
    }

    private void markVersionAwaitingDecision(OutputVersion version, DeliveryRepairService.RepairAssessment assessment) {
        if (version != null && version.getId() != null) {
            version.setStatus("awaiting_decision");
            version.setRepairStrategy(assessment == null ? "awaiting-decision" : assessment.getRecommendedAction());
            outputVersionRepo.save(version);
        }
    }

    private String repairFailureSignature(DeliveryRepairService.RepairAssessment assessment,
                                          RenderService.RenderResult result) {
        String category = assessment == null ? "general" : String.valueOf(assessment.getCategory());
        String evidence = assessment == null ? null : assessment.getEvidence();
        if (evidence == null || evidence.isBlank()) evidence = result == null ? "unknown" : result.getError();
        return category + ":" + String.valueOf(evidence).replaceAll("\\s+", " ").trim();
    }

    private void markVersionRolledBack(OutputVersion version, String afterQc) {
        if (version == null || version.getId() == null || "passed".equals(version.getStatus())) return;
        version.setStatus("rolled_back");
        outputVersionRepo.save(version);
        completeRepairAfterQc(version, afterQc, false, "后续候选未能改善当前问题，保留该版本作为回滚证据");
    }

    /** A later candidate passed full QC, so this failed repair attempt is historical evidence only. */
    private void markVersionSuperseded(OutputVersion version) {
        if (version == null || version.getId() == null || "passed".equals(version.getStatus())) return;
        version.setStatus("rolled_back");
        outputVersionRepo.save(version);
    }

    private void completeRepairAfterQc(OutputVersion repairedVersion, String afterQc, boolean improved, String error) {
        if (repairedVersion == null || repairedVersion.getId() == null) return;
        for (OutputRepair repair : outputRepairRepo.findByOutputVersionIdOrderByIdAsc(repairedVersion.getId())) {
            if (!"approved_auto".equals(repair.getStatus()) && !"approved_manual".equals(repair.getStatus())) continue;
            repair.setAfterQc(afterQc);
            repair.setStatus(improved ? "completed" : "no_improvement");
            repair.setError(error);
            outputRepairRepo.save(repair);
        }
    }

    private void completePendingManualRepairs(Long jobId, int idx, String afterQc, boolean improved, String error) {
        for (OutputRepair repair : outputRepairRepo.findByJobIdAndIdxOrderByIdAsc(jobId, idx)) {
            if (!"approved_manual".equals(repair.getStatus())) continue;
            repair.setAfterQc(afterQc);
            repair.setStatus(improved ? "completed" : "no_improvement");
            repair.setError(error);
            outputRepairRepo.save(repair);
        }
    }

    /** 重试时使用全新上下文，避免复用一个已经被工作流填充过的池。 */
    private SkillEngine.Ctx freshCtx(MixParams itemParams, NarrationService.NarrationResult ignored) {
        SkillEngine.Ctx ctx = new SkillEngine.Ctx();
        if (itemParams.getHookText() != null && !itemParams.getHookText().isBlank()) {
            ctx.setHookText(itemParams.getHookText());
        }
        return ctx;
    }

    private boolean mentionsBgm(String error) {
        if (error == null) return false;
        String text = error.toLowerCase();
        return text.contains("bgm") || text.contains("背景音乐") || text.contains("背景声");
    }

    /** 汇总可解释的降级信息（语义网格回退、音频回退、渲染告警、自动重试）。 */
    private String buildDowngradeInfo(MixPlanner.Plan plan, RenderService.RenderResult result, int retries) {
        List<String> items = new ArrayList<>();
        if (retries > 0) items.add("触发 " + retries + " 次自动重试（切换钩子策略/素材变体）");
        if (plan != null && plan.getGridFallbackCount() > 0) {
            items.add(plan.getGridFallbackCount() + " 条素材缺少结构化镜头分析，已回退网格切片");
        }
        if (plan != null) {
            for (String note : plan.getNotes()) {
                if (note != null && (note.contains("回退") || note.contains("降级") || note.contains("退回")
                        || note.contains("已切换素材变体"))) {
                    items.add(note);
                }
            }
        }
        if (result != null && result.getWarnings() != null) {
            for (String warning : result.getWarnings()) {
                if (warning != null && (warning.contains("回退") || warning.contains("降级") || warning.contains("跳过")
                        || warning.contains("失败") || warning.contains("退回"))) {
                    items.add(warning);
                }
            }
        }
        if (items.isEmpty()) return null;
        try {
            return om.writeValueAsString(items);
        } catch (Exception e) {
            return null;
        }
    }

    /** 成片使用素材时间线（材料 id/名称/角色/起点/时长），供成片库展示。 */
    private String buildUsedMaterials(MixPlanner.Plan plan) {
        if (plan == null || plan.getSegments().isEmpty()) return null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MixPlanner.Segment segment : plan.getSegments()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", segment.getMaterialId());
            row.put("name", segment.getMaterialName());
            row.put("slot", segment.getSlot());
            row.put("kind", segment.getKind());
            row.put("start", segment.getSourceStart());
            row.put("duration", segment.getDuration());
            rows.add(row);
        }
        try {
            return om.writeValueAsString(rows);
        } catch (Exception e) {
            return null;
        }
    }

    /** 一次出片尝试的结果：最终计划、渲染结果、重试次数与最终钩子策略。 */
    private static class RenderAttempt {
        final MixPlanner.Plan plan;
        final RenderService.RenderResult result;
        final int retries;
        final String strategy;
        final boolean awaitingDecision;

        RenderAttempt(MixPlanner.Plan plan, RenderService.RenderResult result, int retries, String strategy) {
            this(plan, result, retries, strategy, false);
        }

        RenderAttempt(MixPlanner.Plan plan, RenderService.RenderResult result, int retries, String strategy, boolean awaitingDecision) {
            this.plan = plan;
            this.result = result;
            this.retries = retries;
            this.strategy = strategy;
            this.awaitingDecision = awaitingDecision;
        }
    }

    private Set<String> loadUsedSegments(Long jobId) {
        Set<String> used = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (JobOutput output : outputRepo.findByJobIdOrderByIdxAsc(jobId)) {
            String serialized = output.getSegmentKeys();
            if (serialized == null || serialized.isBlank()) continue;
            try {
                String[] keys = om.readValue(serialized, String[].class);
                Collections.addAll(used, keys);
            } catch (Exception malformed) {
                log.warn("job output {} has unreadable segment keys; preserving legacy compatibility", output.getId());
            }
        }
        return used;
    }

    /** Load fuzzy segment keys from recent outputs across all jobs for the same project. */
    private Set<String> loadProjectUsedFuzzyKeys(Long projectId, Long excludeJobId) {
        if (projectId == null) return new HashSet<>();
        Set<String> used = new HashSet<>();
        List<Job> recentJobs = jobRepo.findTop100ByProjectIdOrderByIdDesc(projectId);
        for (Job job : recentJobs) {
            if (excludeJobId != null && Objects.equals(job.getId(), excludeJobId)) continue;
            if (JobStatus.pending.name().equals(job.getStatus())
                    || JobStatus.running.name().equals(job.getStatus())) continue;
            for (JobOutput output : outputRepo.findByJobIdOrderByIdxAsc(job.getId())) {
                String serialized = output.getSegmentKeys();
                if (serialized == null || serialized.isBlank()) continue;
                try {
                    String[] keys = om.readValue(serialized, String[].class);
                    // Convert exact keys to fuzzy keys for near-overlap comparison
                    for (String key : keys) parseToFuzzyKey(key).ifPresent(used::add);
                } catch (Exception malformed) {
                    log.warn("project job output {} has unreadable segment keys", output.getId());
                }
            }
        }
        return used;
    }

    /**
     * Counts BGM and voice IDs from recent versions of the same project. This is intentionally
     * advisory: a user-selected audio material remains authoritative, while automatic selection
     * avoids recently used tracks whenever the candidate pool has an alternative.
     */
    private Map<String, Integer> loadProjectRecentAudioUsage(Long projectId, Long excludeJobId) {
        if (projectId == null) return new LinkedHashMap<>();
        Map<String, Integer> usage = new LinkedHashMap<>();
        for (Job historical : jobRepo.findTop100ByProjectIdOrderByIdDesc(projectId)) {
            if (excludeJobId != null && Objects.equals(historical.getId(), excludeJobId)) continue;
            for (OutputVersion version : outputVersionRepo.findByJobIdOrderByIdxAscVersionNoAsc(historical.getId())) {
                if (version.getPlanSnapshot() == null || version.getPlanSnapshot().isBlank()) continue;
                try {
                    var snapshot = om.readTree(version.getPlanSnapshot());
                    countAudioUsage(usage, "bgm", snapshot.path("bgmMaterialId").asLong(0));
                    countAudioUsage(usage, "voice", snapshot.path("voiceMaterialId").asLong(0));
                } catch (Exception malformed) {
                    log.debug("project output version {} has unreadable audio snapshot", version.getId());
                }
            }
        }
        return usage;
    }

    private void countAudioUsage(Map<String, Integer> usage, String category, long materialId) {
        if (materialId > 0) usage.merge(category + ":" + materialId, 1, Integer::sum);
    }

    /** Carries successful automatic audio choices forward to the next output in this batch. */
    private void recordBatchAudioUsage(Map<String, Integer> usage, MixPlanner.Plan plan,
                                       List<String> warnings, int idx) {
        if (usage == null || plan == null) return;
        long bgmId = plan.getBgmMaterialId() == null ? 0 : plan.getBgmMaterialId();
        long voiceId = plan.getVoiceMaterialId() == null ? 0 : plan.getVoiceMaterialId();
        countAudioUsage(usage, "bgm", bgmId);
        countAudioUsage(usage, "voice", voiceId);
        if (bgmId > 0 || voiceId > 0) {
            warnings.add("第 " + idx + " 条已记录本批音频选择，下一条将优先轮换不同可读音轨");
        }
    }

    /** Parse "materialId@start+duration" into fuzzy key with 0.5s granularity. */
    private static Optional<String> parseToFuzzyKey(String exactKey) {
        try {
            // Format: "123@45.678+3.456"
            int atIdx = exactKey.indexOf('@');
            int plusIdx = exactKey.indexOf('+', atIdx + 1);
            if (atIdx < 0 || plusIdx < 0) return Optional.empty();
            String mid = exactKey.substring(0, atIdx);
            double start = Double.parseDouble(exactKey.substring(atIdx + 1, plusIdx));
            double dur = Double.parseDouble(exactKey.substring(plusIdx + 1));
            double fs = Math.round(start * 2.0) / 2.0;
            double fd = Math.round(dur * 2.0) / 2.0;
            return Optional.of(mid + "@" + String.format(Locale.ROOT, "%.1f", fs)
                    + "+" + String.format(Locale.ROOT, "%.1f", fd));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean hasSuccessfulOutput(Long jobId, int idx) {
        return outputRepo.findByJobIdAndIdx(jobId, idx)
                .map(output -> !"fail".equalsIgnoreCase(output.getQcStatus()) && output.getFilePath() != null
                        && !output.getFilePath().isBlank())
                .orElse(false);
    }

    private void discardRenderResult(RenderService.RenderResult result) {
        if (result == null || result.getFilePath() == null || result.getFilePath().isBlank()) return;
        try {
            Files.deleteIfExists(Path.of(result.getFilePath()));
        } catch (Exception e) {
            log.warn("cannot discard output after terminal job state: {}", e.toString());
        }
    }

    /** 每个已持久化的 idx 都是恢复检查点；不要重新渲染或覆盖已有输出。 */
    private Set<Integer> successfulIndexes(Long jobId, int total) {
        Set<Integer> indexes = new HashSet<>();
        for (JobOutput output : outputRepo.findByJobIdOrderByIdxAsc(jobId)) {
            Integer idx = output.getIdx();
            if (idx != null && idx >= 1 && idx <= total && !"fail".equalsIgnoreCase(output.getQcStatus())
                    && output.getFilePath() != null && !output.getFilePath().isBlank()) indexes.add(idx);
        }
        return indexes;
    }

    private String freezeSubmission(Long workflowId, Long projectId, String submittedJson) {
        try {
            Project project = projectId == null ? null : projectRepo.findById(projectId).orElse(null);
            MixParams effective = mergeProjectDefaults(submittedJson, project);
            enrichFolderStepSnapshots(effective);
            if (effective.getSeed() == null) {
                effective.setSeed(stableSeed(workflowId, projectId, om.writeValueAsString(effective)));
            }
            com.fasterxml.jackson.databind.node.ObjectNode root = om.createObjectNode();
            root.set("effectiveParams", om.valueToTree(effective));
            root.put("snapshotVersion", 1);
            root.put("frozenAt", LocalDateTime.now().toString());
            if (workflowId != null) {
                Workflow workflow = workflowRepo.findById(workflowId).orElse(null);
                if (workflow != null) {
                    root.put("workflowNameSnapshot", workflow.getName());
                    root.put("workflowVersionSnapshot", workflow.getVersion());
                    root.put("workflowDefSnapshot", workflow.getDef());
                }
            }
            if (project != null) {
                root.put("projectNameSnapshot", project.getName());
                root.put("projectDefaultParamsSnapshot", project.getDefaultParams() == null ? "" : project.getDefaultParams());
            }
            return om.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法冻结出片任务配置: " + concise(e));
        }
    }

    private long stableSeed(Long workflowId, Long projectId, String effectiveParams) {
        String value = String.valueOf(workflowId) + ":" + String.valueOf(projectId) + ":" + effectiveParams;
        long hash = 1125899906842597L;
        for (char ch : value.toCharArray()) hash = 31 * hash + ch;
        return hash == Long.MIN_VALUE ? 1L : Math.abs(hash);
    }

    private String updateFrozenParams(String existingJson, MixParams params) throws Exception {
        com.fasterxml.jackson.databind.JsonNode parsed = om.readTree(existingJson == null ? "{}" : existingJson);
        if (parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode root && root.has("effectiveParams")) {
            root.set("effectiveParams", om.valueToTree(params));
            root.put("repairedAt", LocalDateTime.now().toString());
            return om.writeValueAsString(root);
        }
        return om.writeValueAsString(params);
    }

    private void enrichFolderStepSnapshots(MixParams params) {
        if (params == null || params.getFolderReadSteps() == null) return;
        for (MixParams.FolderReadStep step : params.getFolderReadSteps()) {
            if (step == null) continue;
            if (step.getFolderId() != null) folderRepo.findById(step.getFolderId()).ifPresent(folder -> step.setFolderNameSnapshot(folder.getName()));
            if (step.getFallbackFolderId() != null) folderRepo.findById(step.getFallbackFolderId()).ifPresent(folder -> step.setFallbackFolderNameSnapshot(folder.getName()));
        }
    }

    private String resolveWorkflow(Job job) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(job.getParams());
            String snapshot = root.path("workflowDefSnapshot").asText("");
            if (!snapshot.isBlank()) return snapshot;
        } catch (Exception ignored) {
            // Pre-snapshot jobs retain the historical workflow lookup path below.
        }
        if (job.getWorkflowId() != null) {
            Workflow workflow = workflowRepo.findById(job.getWorkflowId()).orElse(null);
            if (workflow != null && workflow.getDef() != null && !workflow.getDef().isBlank()) return workflow.getDef();
        }
        return skillEngine.defaultWorkflowDef();
    }

    /** 持续任务每次只生成一条，成功后即刻继续；连续失败上限防止素材或 ffmpeg 异常时无限空转。 */
    private void runContinuous(Long jobId, Job job, Project project, MixParams params, String def,
                               Set<Integer> successful, Set<String> usedSegments, Map<String, Integer> batchReuseUsage) {
        int consecutiveFailures = 0;
        String lastFailureReason = null;
        Long projectId = job.getProjectId();
        Set<String> projectFuzzyKeys = projectId != null
                ? loadProjectUsedFuzzyKeys(projectId, jobId) : new HashSet<>();
        while (true) {
            Job latest = reloadOrNull(jobId);
            if (latest == null || JobStatus.paused.name().equals(latest.getStatus())) {
                liveStep.remove(jobId);
                return;
            }
            if (isTerminal(latest.getStatus()) || cancelled.contains(jobId)) {
                if (latest != null && JobStatus.cancelled.name().equals(latest.getStatus())) cancelPersisted(latest, successful.size());
                return;
            }
            int idx = nextFreeIndex(successful);
            liveStep.put(jobId, "连续出片：准备第 " + idx + " 条");
            heartbeat(latest, "连续出片：准备第 " + idx + " 条");
            latest.setCurrent(successful.size());
            latest.setSummary("连续出片中，已完成 " + successful.size() + " 条；点击暂停会在当前条完成后停止");
            jobRepo.save(latest);
            try {
                MixParams itemParams = copyParams(params);
                itemParams.setHookStrategy(HookStrategy.select(project, idx - 1).name());
                List<String> hooks = createHooks(project, itemParams, 1, idx - 1,
                        buildHookEvidence(project, itemParams), new ArrayList<>());
                if (!hooks.isEmpty()) {
                    itemParams.setHookText(hooks.get(0));
                    itemParams.setAutoGeneratedHook(true);
                }
                if (Boolean.TRUE.equals(itemParams.getAutoRehook())
                        && (itemParams.getRehookText() == null || itemParams.getRehookText().isBlank())) {
                    itemParams.setRehookText(copyService.rehook(project, null,
                            HookStrategy.safeValueOf(itemParams.getHookStrategy())));
                }
                SkillEngine.Ctx ctx = new SkillEngine.Ctx();
                if (itemParams.getHookText() != null && !itemParams.getHookText().isBlank()) ctx.setHookText(itemParams.getHookText());
                RenderAttempt attempt = renderItemWithRetry(project, itemParams, def, idx - 1, usedSegments,
                        batchReuseUsage, projectFuzzyKeys, jobId, idx, Math.max(1, successful.size() + 1), ctx,
                        new ArrayList<>(), latest);
                MixPlanner.Plan plan = attempt.plan;
                RenderService.RenderResult result = attempt.result;
                if (attempt.awaitingDecision) {
                    saveQcBlockedOutput(jobId, idx, result, plan == null ? Set.of() : plan.segmentKeys(), attempt.retries,
                            plan == null ? null : plan.getHookStrategy(), buildDowngradeInfo(plan, result, attempt.retries),
                            buildUsedMaterials(plan));
                    markAwaitingDecision(latest, idx, result.getError());
                    return;
                }
                if (!plan.isUsable() || missingRequiredAudio(plan) || insufficientAudioCoverage(plan)) {
                    boolean noAudio = missingRequiredAudio(plan);
                    boolean shortAudio = insufficientAudioCoverage(plan);
                    String reason = noAudio ? missingRequiredAudioMessage()
                            : (shortAudio ? insufficientAudioCoverageMessage(plan) : String.join("；", plan.getNotes()));
                    Job failed = reloadOrNull(jobId);
                    if (failed != null) {
                        String prefix = noAudio ? "连续出片已停止：当前计划没有可用音轨。"
                                : (shortAudio ? "连续出片已停止：当前口播无法覆盖全片。"
                                : "连续出片已停止：当前素材无法满足时长要求。");
                        markFailed(failed, prefix
                                + (reason.isBlank() ? "请补充可读素材或降低目标时长后重新干跑。" : reason));
                    }
                    return;
                }
                Job afterRender = reloadOrNull(jobId);
                if (afterRender == null || JobStatus.cancelled.name().equals(afterRender.getStatus()) || cancelled.contains(jobId)) {
                    discardRenderResult(result);
                    if (afterRender != null) cancelPersisted(afterRender, successful.size());
                    return;
                }
                if (result.isOk() && saveOutputIfAbsent(jobId, idx, result, plan.segmentKeys(), attempt.retries,
                        plan.getHookStrategy(), buildDowngradeInfo(plan, result, attempt.retries),
                        buildUsedMaterials(plan), result.getQcJson())) {
                    usedSegments.addAll(plan.segmentKeys());
                    recordBatchAudioUsage(params.getRecentAudioUsage(), plan, new ArrayList<>(), idx);
                    recordBatchReuse(batchReuseUsage, plan);
                    successful.add(idx);
                    consecutiveFailures = 0;
                    lastFailureReason = null;
                } else if (hasSuccessfulOutput(jobId, idx)) {
                    successful.add(idx);
                    consecutiveFailures = 0;
                    lastFailureReason = null;
                } else {
                    if ("fail".equals(result.getQcStatus())) {
                        saveQcBlockedOutput(jobId, idx, result, plan.segmentKeys(), attempt.retries,
                                plan.getHookStrategy(), buildDowngradeInfo(plan, result, attempt.retries),
                                buildUsedMaterials(plan));
                    }
                    consecutiveFailures++;
                    lastFailureReason = result.isOk()
                            ? "成片已渲染但保存记录失败"
                            : truncate(result.getError());
                }
                if (JobStatus.paused.name().equals(afterRender.getStatus())) {
                    afterRender.setCurrent(successful.size());
                    afterRender.setSummary("已暂停，已保留 " + successful.size() + " 条成片；点击继续会从下一条开始");
                    heartbeat(afterRender, "已暂停");
                    jobRepo.save(afterRender);
                    liveStep.remove(jobId);
                    return;
                }
            } catch (Exception e) {
                consecutiveFailures++;
                lastFailureReason = concise(e);
                log.error("continuous job {} item failed", jobId, e);
            }
            if (consecutiveFailures >= 5) {
                Job failed = reloadOrNull(jobId);
                if (failed != null) {
                    String detail = lastFailureReason == null || lastFailureReason.isBlank()
                            ? "未返回具体错误，请检查后端日志"
                            : lastFailureReason;
                    markFailed(failed, "连续 5 条渲染失败，已自动停止。最后一次失败：" + detail);
                }
                return;
            }
        }
    }

    private boolean missingRequiredAudio(MixPlanner.Plan plan) {
        return plan.isRequiresExternalAudio()
                && (plan.getVoicePath() == null || plan.getVoicePath().isBlank())
                && (plan.getBgmPath() == null || plan.getBgmPath().isBlank());
    }

    private boolean insufficientAudioCoverage(MixPlanner.Plan plan) {
        return plan.isRequiresExternalAudio()
                && (plan.getBgmPath() == null || plan.getBgmPath().isBlank())
                && plan.getVoicePath() != null && !plan.getVoicePath().isBlank()
                && plan.getVoiceDurationSec() + 0.5 < plan.getPlannedSec();
    }

    private String missingRequiredAudioMessage() {
        return "请至少导入一条 BGM或口播，或在出片参数中明确选择保留原片声音/AI 人声。";
    }

    private String insufficientAudioCoverageMessage(MixPlanner.Plan plan) {
        return "口播仅 " + FfmpegTool.trimNum(plan.getVoiceDurationSec()) + "s，计划 "
                + FfmpegTool.trimNum(plan.getPlannedSec()) + "s；未选择 BGM 会产生长静音。请指定任意音频作为 BGM、补足口播，或选择保留原片声音。";
    }

    private int nextFreeIndex(Set<Integer> successful) {
        int idx = 1;
        while (successful.contains(idx)) idx++;
        return idx;
    }

    private NarrationService.NarrationResult prepareAiVoice(Project project, MixParams params, SkillEngine.Ctx ctx,
                                                            List<String> warnings, Long jobId, int idx, double plannedSec,
                                                            MixPlanner.Plan plan) {
        if (!"ai-voice".equalsIgnoreCase(params.getAudioMode())) return null;
        if (params.getVoiceMaterialId() != null) {
            // An explicit material voice is authoritative, even if an old client submits ai-voice too.
            ctx.log("已指定素材口播，跳过 AI 配音生成");
            return null;
        }
        try {
            HookStrategy strategy = HookStrategy.safeValueOf(params.getHookStrategy());
            int seconds = scriptSeconds(params, plannedSec);
            Set<String> existingScripts = narrationFingerprints(jobId);
            String script = null;
            for (int generation = 0; generation < 3; generation++) {
                String extra = generation == 0 ? null
                        : "与本批已用口播必须换一个卖点切入、句式和行动引导，禁止复用此前表达。";
                String candidate = copyService.script(project, seconds, extra,
                        params.getHookText(), strategy, idx + generation * 37, materialSummary(plan));
                if (candidate == null || candidate.isBlank()) continue;
                if (existingScripts.add(narrationFingerprint(candidate))) {
                    script = candidate;
                    break;
                }
            }
            if (script == null) throw new IllegalStateException("AI 口播与同批已生成文案重复，已拒绝复用；请减少批量数量或补充不同卖点后重试");
            NarrationService.NarrationResult result = narrationService.generate(script, params.getTtsVoice(), jobId, idx, (int) Math.floor(plannedSec));
            double voiceDuration = result.voice().getDurationSec() == null ? 0 : result.voice().getDurationSec();
            if (plannedSec > 0 && Math.abs(voiceDuration - plannedSec) > Math.max(1.5, plannedSec * 0.08)) {
                throw new IllegalStateException("AI 口播实测 " + FfmpegTool.trimNum(voiceDuration) + "s，与计划 "
                        + FfmpegTool.trimNum(plannedSec) + "s 不匹配；已拒绝截断或静默补齐");
            }
            params.setVoiceMaterialId(result.voice().getId());
            params.setAutoMatchAudio(false);
            ctx.setScript(script);
            ctx.log("AI 人声已生成：" + result.voice().getName()
                    + (result.cues().isEmpty() ? "；ASR 未返回字幕时间轴" : "；已用 ASR 对齐字幕时间轴"));
            return result;
        } catch (Exception e) {
            String message = "AI 自然人声不可用：" + concise(e) + "。未自动替换为素材口播或其他声音，请检查后重新主动提交。";
            warnings.add(message);
            ctx.log("AI 人声失败，未自动切换其他声音模式");
            throw new IllegalStateException(message, e);
        }
    }

    /** Updates an automatic hook only after the real visual plan is available. */
    private void refreshAutomaticHook(Project project, MixParams params, MixPlanner.Plan plan,
                                      SkillEngine.Ctx ctx, List<String> warnings, int idx) {
        if (!Boolean.TRUE.equals(params.getAutoGeneratedHook()) || plan == null) return;
        try {
            String hook = copyService.hook(project, materialSummary(plan),
                    HookStrategy.safeValueOf(params.getHookStrategy()));
            if (hook == null || hook.isBlank()) return;
            params.setHookText(hook);
            plan.setHookText(hook);
            ctx.setHookText(hook);
        } catch (Exception e) {
            warnings.add("第 " + idx + " 条未能按实际素材刷新钩子：" + concise(e));
        }
    }

    /**
     * Supplies an automatic retry hook with the user's explicitly scoped material evidence.
     * No unscoped disk locations are read here; a missing explicit scope intentionally yields
     * a conservative brief instead of inventing a product category.
     */
    private String buildHookEvidence(Project project, MixParams params) {
        if (params == null || params.getMaterialIds() == null || params.getMaterialIds().isEmpty()) {
            return project == null ? "当前授权素材未提供可验证的产品信息；用保守提问式开场，不虚构品类或功效。" : "";
        }
        StringBuilder evidence = new StringBuilder("当前授权素材：");
        int count = 0;
        for (Long id : params.getMaterialIds()) {
            if (id == null || count >= 6) continue;
            Material material = materialStore.findById(id).orElse(null);
            if (material == null) continue;
            if (count++ > 0) evidence.append("；");
            evidence.append(material.getName() == null ? "未命名素材" : material.getName());
            if (material.getRole() != null) evidence.append("（").append(material.getRole()).append("）");
            if (material.getTags() != null && !material.getTags().isBlank()) evidence.append("，标签：").append(material.getTags());
        }
        return count == 0 ? (project == null ? "当前授权素材未提供可验证的产品信息；用保守提问式开场，不虚构品类或功效。" : "")
                : evidence.toString();
    }

    /**
     * Summarize the visual/audio content of the planned materials so the narration
     * script talks about what is actually on screen instead of generic copy.
     */
    private String materialSummary(MixPlanner.Plan plan) {
        if (plan == null || plan.getSegments() == null || plan.getSegments().isEmpty()) return "";
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        for (MixPlanner.Segment seg : plan.getSegments()) {
            if (seg.getMaterialId() != null) ids.add(seg.getMaterialId());
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Long id : ids) {
            Material material = id == null ? null : materialStore.findById(id).orElse(null);
            if (material == null) continue;
            StringBuilder part = new StringBuilder(material.getName() == null ? "素材" : material.getName());
            try {
                List<String> ocr = List.of();
                if (count < 2) {
                    try {
                        ocr = SUMMARY_OCR.submit(() -> materialDiagnosisService.readOcrTexts(material))
                                .get(8, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        ocr = List.of(); // OCR 超时/失败只影响内容感知，不阻塞出片
                    }
                }
                if (!ocr.isEmpty()) {
                    List<String> top = ocr.size() > 6 ? ocr.subList(0, 6) : ocr;
                    part.append("；画面文字：").append(String.join("，", top));
                }
                List<MaterialDiagnosisService.TranscriptCue> cues = materialDiagnosisService.getCachedTranscript(id);
                StringBuilder spoken = new StringBuilder();
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                for (MaterialDiagnosisService.TranscriptCue cue : cues) {
                    String t = cue.getText();
                    if (t != null && !t.isBlank() && seen.add(t)) {
                        spoken.append(t).append("；");
                        if (seen.size() >= 5) break;
                    }
                }
                if (spoken.length() > 0) part.append("；口播内容：").append(spoken);
            } catch (Exception ignore) {
                // 识别失败只影响内容感知，不阻断出片。
            }
            sb.append("素材").append(++count).append("：").append(part).append("\n");
            if (sb.length() > 900) break;
        }
        return sb.toString();
    }
    private Set<String> narrationFingerprints(Long jobId) {
        Set<String> fingerprints = new HashSet<>();
        for (String script : narrationService.scriptsByJobId(jobId)) {
            fingerprints.add(narrationFingerprint(script));
        }
        return fingerprints;
    }

    private String narrationFingerprint(String script) {
        return script == null ? "" : script.replaceAll("[\\s，。！？、,.!?:：;；]", "")
                .toLowerCase(Locale.ROOT);
    }

    private int scriptSeconds(MixParams params, double plannedSec) {
        if (plannedSec > 0) return Math.max(15, Math.min(300, (int) Math.floor(plannedSec)));
        if (params == null) return 60;
        Integer target = params.getTargetDurationSec() != null ? params.getTargetDurationSec() : params.getTargetSec();
        if (target == null) target = params.getMaxSec() != null ? params.getMaxSec() : 60;
        return Math.max(15, Math.min(300, target));
    }

    /** Attach real ASR narration cues to the plan so the render path can burn AI voice subtitles. */
    private void attachNarration(MixPlanner.Plan plan, NarrationService.NarrationResult narration) {
        if (plan == null || narration == null) return;
        plan.setNarrationScriptText(narration.script());
        List<MixPlanner.Plan.CaptionCue> cues = new ArrayList<>();
        for (MaterialDiagnosisService.TranscriptCue cue : narration.cues()) {
            MixPlanner.Plan.CaptionCue captionCue = new MixPlanner.Plan.CaptionCue();
            captionCue.setStart(cue.getStart());
            captionCue.setEnd(cue.getEnd());
            captionCue.setText(cue.getText());
            cues.add(captionCue);
        }
        plan.setNarrationCaptions(cues);
    }

    private MixPlanner.Plan batchSafePlan(String definition, Project project, MixParams params, int variant,
                                           Set<String> usedSegments, Map<String, Integer> batchReuseUsage,
                                           java.util.function.Consumer<String> onStep, SkillEngine.Ctx context,
                                           Long jobId, Set<String> projectFuzzyKeys) {
        MixPlanner.Plan last = null;
        for (int offset = 0; offset < 16; offset++) {
            SkillEngine.Ctx candidateContext = offset == 0 ? context : new SkillEngine.Ctx();
            MixPlanner.Plan candidate = uniquePlan(definition, project, params, variant + offset * 97,
                    usedSegments, onStep, candidateContext, jobId, projectFuzzyKeys);
            last = candidate;
            if (!candidate.isUsable()) return candidate;
            if (!exceedsBatchReuse(candidate, batchReuseUsage, 2)) {
                if (offset > 0) candidate.getNotes().add("已切换批次变体，确保同一音频和开头画面最多使用 2 次");
                return candidate;
            }
        }
        if (last == null) last = new MixPlanner.Plan();
        last.setSegments(new ArrayList<>());
        last.setPlannedSec(0);
        last.getNotes().add("本批次可用音频或开头画面不足：同一素材最多使用 2 次。请补充音频/钩子素材，或缩小本次数量后重试");
        return last;
    }

    private boolean exceedsBatchReuse(MixPlanner.Plan plan, Map<String, Integer> usage, int limit) {
        if (usage == null || usage.isEmpty()) return false;
        return batchReuseKeys(plan).stream().anyMatch(key -> usage.getOrDefault(key, 0) >= limit);
    }

    private Set<String> batchReuseKeys(MixPlanner.Plan plan) {
        Set<String> keys = new LinkedHashSet<>();
        if (plan == null) return keys;
        if (plan.getBgmMaterialId() != null) keys.add("audio:" + plan.getBgmMaterialId());
        if (plan.getVoiceMaterialId() != null) keys.add("audio:" + plan.getVoiceMaterialId());
        if (plan.getHookAudioMaterialId() != null) keys.add("audio:" + plan.getHookAudioMaterialId());
        MixPlanner.Segment hook = plan.getSegments().stream().filter(segment -> "hook".equals(segment.getSlot())).findFirst()
                .orElse(plan.getSegments().isEmpty() ? null : plan.getSegments().get(0));
        if (hook != null && hook.getMaterialId() != null) keys.add("hook:" + hook.getMaterialId());
        return keys;
    }

    private void recordBatchReuse(Map<String, Integer> usage, MixPlanner.Plan plan) {
        if (usage == null) return;
        for (String key : batchReuseKeys(plan)) usage.merge(key, 1, Integer::sum);
    }

    private MixPlanner.Plan uniquePlan(String definition, Project project, MixParams params, int variant,
                                       Set<String> usedSegments, java.util.function.Consumer<String> onStep,
                                       SkillEngine.Ctx context, Long jobId, Set<String> projectFuzzyKeys) {
        MixPlanner.Plan last = null;
        // Stable cross-job variant: derive from job identity so same job+params always reproduces
        long effectiveVariant = jobId != null ? (jobId * 31L + variant) : variant;
        int maxAttempts = Math.max(12, Math.min(24, 12 + (usedSegments.size() / 15)));
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            SkillEngine.Ctx current = attempt == 0 ? context : new SkillEngine.Ctx();
            MixPlanner.Plan candidate = skillEngine.run(definition, project, params,
                    (int)(effectiveVariant + attempt * 101L), onStep, current);
            last = candidate;
            // A duplicate-only rejection can be fixed by another deterministic variant.
            // Other unusable plans (for example insufficient duration) must fail fast.
            if (!candidate.isUsable()) {
                if (!candidate.isInternallyUnique() && attempt + 1 < maxAttempts) continue;
                return candidate;
            }
            // Check exact overlap within the same job
            Set<String> overlap = new java.util.HashSet<>(candidate.segmentKeys());
            overlap.retainAll(usedSegments);
            // Check near-overlap across the project's recent outputs
            boolean nearOverlap = projectFuzzyKeys != null && !projectFuzzyKeys.isEmpty()
                    && candidate.hasNearOverlap(projectFuzzyKeys, 0.30);
            if (overlap.isEmpty() && !nearOverlap) {
                if (attempt > 0) candidate.getNotes().add("已切换素材变体，避免与同批成片重复画面");
                return candidate;
            }
            if (nearOverlap) {
                candidate.getNotes().add("检测到与近期项目成片画面重叠，尝试切换素材变体");
            }
        }
        if (last == null) last = new MixPlanner.Plan();
        if (last.isUsable()) {
            // Cross-job overlap is a quality preference, not a reason to discard an otherwise deliverable plan.
            // Keep the best eligible variant and surface the repetition risk for review.
            last.getNotes().add("近期项目素材已高度复用；已保留当前可交付变体并尽力错开切片，建议后续补充新画面以提升差异化");
            return last;
        }
        // Only a genuinely unusable plan should stop the job.
        last.getNotes().add("素材切片不足以生成可交付计划；已尝试 " + maxAttempts
                + " 个变体。请补充相关素材或降低目标时长");
        return last;
    }

    private List<String> createHooks(Project project, MixParams params, int count, int startVariant,
                                     String evidence, List<String> warnings) {
        if (count == 0 || !Boolean.TRUE.equals(params.getAiHook())
                || (params.getHookText() != null && !params.getHookText().isBlank())) return Collections.emptyList();
        try {
            List<HookStrategy> strategies = new ArrayList<>();
            for (int i = 0; i < count; i++) strategies.add(HookStrategy.select(project, Math.max(0, startVariant) + i));
            return copyService.hooks(project, count, evidence, strategies);
        } catch (Exception e) {
            warnings.add("批量钩子生成失败: " + concise(e));
            log.warn("批量钩子生成失败: {}", e.toString());
            return Collections.emptyList();
        }
    }

    private void updateStep(Long jobId, int idx, int total, String step) {
        String message = "第 " + idx + "/" + total + " 条：" + step;
        liveStep.put(jobId, message);
        livePhaseProgress.put(jobId, phaseProgress(step));
        // 避免每个 ffmpeg 子步骤高频 update；activity 只需为 watchdog 提供心跳。
        try {
            jobRepo.findById(jobId).ifPresent(job -> {
                if (JobStatus.running.name().equals(job.getStatus())) {
                    heartbeat(job, message);
                    job.setCurrentStep(message);
                    job.setPhaseProgress(phaseProgress(step));
                    jobRepo.save(job);
                }
            });
        } catch (Exception e) {
            log.debug("job {} heartbeat update failed: {}", jobId, e.toString());
        }
    }

    private void cancelPersisted(Job job, int ok) {
        job.setStatus(JobStatus.cancelled.name());
        job.setCurrent(ok);
        job.setProgress(progress(ok, totalFor(job)));
        job.setSummary("已取消，已完成 " + ok + " 条");
        job.setError(null);
        heartbeat(job, "已取消");
        jobRepo.save(job);
    }

    private void markAwaitingDecision(Job job, int idx, String reason) {
        if (job == null || JobStatus.cancelled.name().equals(job.getStatus()) || cancelled.contains(job.getId())) return;
        job.setStatus(JobStatus.awaiting_decision.name());
        job.setSummary("第 " + idx + " 条等待人工决策，已保留候选版本与质检证据");
        job.setError(truncate(reason));
        heartbeat(job, "等待人工决策");
        jobRepo.save(job);
    }

    private void markFailed(Job job, String message) {
        if (JobStatus.cancelled.name().equals(job.getStatus()) || cancelled.contains(job.getId())) {
            cancelPersisted(job, successfulIndexes(job.getId(), totalFor(job)).size());
            return;
        }
        if (isTerminal(job.getStatus())) return;
        job.setStatus(JobStatus.failed.name());
        int completed = Math.max(0, job.getCurrent() == null ? 0 : job.getCurrent());
        job.setProgress(progress(completed, totalFor(job)));
        job.setSummary("任务失败：已完成 " + completed + " 条");
        job.setError(truncate(message));
        job.setLastActivityAt(LocalDateTime.now());
        jobRepo.save(job);
    }

    private int phaseProgress(String step) {
        if (step == null) return 0;
        if (step.contains("准备")) return 5;
        if (step.contains("切片")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("切片\\s+(\\d+)/(\\d+)").matcher(step);
            if (matcher.find()) {
                int current = Integer.parseInt(matcher.group(1));
                int total = Math.max(1, Integer.parseInt(matcher.group(2)));
                return Math.min(45, 10 + Math.round(35f * current / total));
            }
            return 10;
        }
        if (step.contains("拼接")) return 60;
        if (step.contains("混音")) return 75;
        if (step.contains("字幕")) return 88;
        if (step.contains("完成")) return 100;
        return 10;
    }

    private void heartbeat(Job job, String ignoredStep) {
        job.setLastActivityAt(LocalDateTime.now());
    }

    private boolean isContinuous(Job job) {
        if (job == null || job.getParams() == null || job.getParams().isBlank()) return false;
        try {
            return om.readTree(job.getParams()).path("continuous").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean exceededJobTimeout(Job job) {
        if (isContinuous(job) || job.getCreatedAt() == null) return false;
        return Duration.between(job.getCreatedAt(), LocalDateTime.now()).getSeconds() >= configuredTimeout(job);
    }

    private boolean isStale(Job job, LocalDateTime now) {
        return Duration.between(activityAt(job, now), now).getSeconds() >= configuredStaleAfter(job);
    }

    private LocalDateTime activityAt(Job job, LocalDateTime fallback) {
        if (job.getLastActivityAt() != null) return job.getLastActivityAt();
        if (job.getUpdatedAt() != null) return job.getUpdatedAt();
        if (job.getCreatedAt() != null) return job.getCreatedAt();
        return fallback;
    }

    private int configuredTimeout(Job job) {
        return positiveOrDefault(job.getTimeoutSec(), props.getJobTimeoutSec(), 7200);
    }

    private int configuredStaleAfter(Job job) {
        return positiveOrDefault(job.getStaleAfterSec(), props.getJobStaleAfterSec(), 900);
    }

    private int positiveOrDefault(Integer jobValue, int appValue, int fallback) {
        int value = jobValue != null && jobValue > 0 ? jobValue : appValue;
        return Math.max(30, value > 0 ? value : fallback);
    }

    private Integer normalizeOptionalSeconds(Integer seconds) {
        return seconds == null || seconds <= 0 ? 0 : Math.min(seconds, 86_400);
    }

    private int totalFor(Job job) {
        int total = job.getTotal() == null || job.getTotal() < 1
                ? (job.getCount() == null ? 1 : job.getCount()) : job.getTotal();
        return Math.max(1, total);
    }

    private int progress(int current, int total) {
        return Math.max(0, Math.min(100, (int) Math.round(current * 100.0 / Math.max(1, total))));
    }

    private boolean isTerminal(String status) {
        return JobStatus.done.name().equals(status) || JobStatus.failed.name().equals(status)
                || JobStatus.cancelled.name().equals(status);
    }

    private String limitWarnings(List<String> warnings) {
        return warnings.isEmpty() ? null : truncate(String.join("\n", warnings));
    }

    private String concise(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private String truncate(String text) {
        return text == null ? null : (text.length() <= ERROR_LIMIT ? text : text.substring(0, ERROR_LIMIT));
    }

    private Job reloadOrNull(Long id) {
        return jobRepo.findById(id).orElse(null);
    }

    private MixParams copyParams(MixParams src) {
        try {
            return om.readValue(om.writeValueAsString(src), MixParams.class);
        } catch (Exception e) {
            return src;
        }
    }

    /** 项目默认参数打底；提交 JSON 中实际出现过的键覆盖项目默认值。 */
    MixParams mergeProjectDefaults(String submittedJson, Project project) {
        MixParams base = new MixParams();
        try {
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(submittedJson == null ? "{}" : submittedJson);
            if (root.has("effectiveParams") && root.path("effectiveParams").isObject()) {
                return om.treeToValue(root.path("effectiveParams"), MixParams.class).normalized();
            }
        } catch (Exception ignored) {
            // Historical jobs use the compatibility merge below.
        }
        if (project != null && project.getDefaultParams() != null && !project.getDefaultParams().isBlank()) {
            try {
                base = om.readValue(project.getDefaultParams(), MixParams.class);
            } catch (Exception e) {
                log.debug("项目默认参数解析失败: {}", e.toString());
            }
        }
        if (submittedJson != null && !submittedJson.isBlank() && !"{}".equals(submittedJson.trim())) {
            try {
                MixParams merged = om.readerForUpdating(base).readValue(submittedJson);
                return merged.normalized();
            } catch (Exception e) {
                log.warn("出片参数解析失败，使用项目默认值: {}", e.toString());
            }
        }
        return base.normalized();
    }

    // ---------------- 查询 ----------------

    public List<Job> recent() {
        return jobRepo.findTop100ByOrderByIdDesc();
    }

    public List<JobOutput> outputs(Long jobId) {
        return outputRepo.findByJobIdOrderByIdxAsc(jobId);
    }

    @Transactional
    public List<OutputVersion> outputVersions(Long jobId, int idx) {
        List<OutputVersion> versions = outputVersionRepo.findByJobIdAndIdxOrderByVersionNoAsc(jobId, idx);
        boolean laterPassed = versions.stream().anyMatch(version -> "passed".equals(version.getStatus())
                && (version.getVersionNo() == null || version.getVersionNo() > 1));
        if (laterPassed) {
            for (OutputVersion version : versions) {
                if ("repairing".equals(version.getStatus())) {
                    version.setStatus("rolled_back");
                    outputVersionRepo.save(version);
                }
            }
        }
        return versions;
    }

    public List<OutputRepair> outputRepairs(Long jobId, int idx) {
        return outputRepairRepo.findByJobIdAndIdxOrderByIdAsc(jobId, idx);
    }

    /** 人工决策只允许固定策略；可恢复任务仍通过现有队列和检查点执行。 */
    @Transactional
    public void applyRepairDecision(Long jobId, int idx, String action, Long bgmMaterialId) {
        Job job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (JobStatus.running.name().equals(job.getStatus()) || JobStatus.pending.name().equals(job.getStatus())) {
            throw new IllegalArgumentException("任务正在执行，不能修改修复决策");
        }
        Set<String> allowed = Set.of("replace-bgm", "keep-original-audio", "relax-subtitle", "regenerate-plan", "switch-hook", "retry-auto");
        if (!allowed.contains(action)) throw new IllegalArgumentException("不支持的修复动作");
        MixParams params = mergeProjectDefaults(job.getParams(), job.getProjectId() == null ? null : projectRepo.findById(job.getProjectId()).orElse(null));
        if ("replace-bgm".equals(action)) {
            if (bgmMaterialId == null) throw new IllegalArgumentException("请选择一条背景音乐");
            Material material = materialStore.findById(bgmMaterialId).orElseThrow(() -> new IllegalArgumentException("背景音乐不存在"));
            if (!deliveryRepairService.isReadableBgm(material)) {
                throw new IllegalArgumentException("所选背景音乐无法解码或没有有效音轨");
            }
            params.setBgmMaterialId(material.getId());
            params.setAudioMode("material-audio");
        } else if ("keep-original-audio".equals(action)) {
            params.setAudioMode("original");
        } else if ("relax-subtitle".equals(action)) {
            params.setAutoSubtitles(false);
            params.setBurnAiVoiceCaptions(false);
        } else if ("switch-hook".equals(action)) {
            params.setHookStrategy(null);
            params.setAutoRehook(false);
        } else if ("regenerate-plan".equals(action)) {
            params.setSeed(System.nanoTime());
        }
        try {
            job.setParams(updateFrozenParams(job.getParams(), params.normalized()));
        } catch (Exception e) {
            throw new IllegalStateException("无法保存修复参数", e);
        }
        for (OutputRepair repair : outputRepairRepo.findByJobIdAndIdxOrderByIdAsc(jobId, idx)) {
            if ("awaiting_decision".equals(repair.getStatus()) || "proposed".equals(repair.getStatus())) {
                repair.setSelectedAction(action);
                repair.setStatus("approved_manual");
                repair.setExecutionImpact("用户选择 " + action + " 后重新进入现有渲染队列");
                outputRepairRepo.save(repair);
            }
        }
        job.setStatus(JobStatus.pending.name());
        job.setSummary("已接收第 " + idx + " 条修复决策，正在重新排队");
        job.setError(null);
        heartbeat(job, "人工修复决策已确认");
        jobRepo.save(job);
        dispatch(jobId);
    }

    /**
     * 成片库只返回通过新渲染器时长校验的正常记录。
     * 历史版本可能留下几小时的循环无效文件；不自动删除磁盘文件，先从正常成片库隔离。
     */
    public List<JobOutput> allOutputs() {
        return outputRepo.findTop200ByOrderByIdDesc().stream()
                .filter(output -> "fail".equalsIgnoreCase(output.getQcStatus())
                        || (output.getDurationSec() != null && output.getDurationSec() >= 1
                        && output.getDurationSec() <= 300))
                .toList();
    }

    @Transactional
    public int cleanupTerminal() {
        int deleted = 0;
        // 全量清理所有终态任务,不再受 recent()=最近 100 条的限制
        List<Job> terminal = new ArrayList<>();
        for (String status : List.of(JobStatus.done.name(), JobStatus.failed.name(), JobStatus.cancelled.name())) {
            terminal.addAll(jobRepo.findByStatus(status));
        }
        for (Job job : terminal) {
            deleteJob(job.getId());
            deleted++;
        }
        return deleted;
    }

    @Transactional
    public void deleteJob(Long jobId) {
        Job job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;
        if (JobStatus.running.name().equals(job.getStatus()) || JobStatus.pending.name().equals(job.getStatus())
                || JobStatus.paused.name().equals(job.getStatus())) {
            throw new IllegalArgumentException("运行中或已暂停的任务请先取消，确认停止后再删除记录");
        }
        Set<String> candidatePaths = new HashSet<>();
        for (OutputVersion version : outputVersionRepo.findByJobIdOrderByIdxAscVersionNoAsc(jobId)) {
            if (version.getFilePath() != null && !version.getFilePath().isBlank()) candidatePaths.add(version.getFilePath());
        }
        for (JobOutput output : outputRepo.findByJobIdOrderByIdxAsc(jobId)) {
            if (output.getFilePath() != null && !output.getFilePath().isBlank()) candidatePaths.add(output.getFilePath());
        }
        for (String filePath : candidatePaths) {
            try {
                Files.deleteIfExists(Path.of(filePath));
            } catch (Exception e) {
                log.warn("unable to delete output file for job {}: {}", jobId, e.toString());
            }
        }
        outputRepairRepo.deleteByJobId(jobId);
        outputVersionRepo.deleteByJobId(jobId);
        outputRepo.deleteByJobId(jobId);
        narrationService.deleteByJobId(jobId);
        editorialBriefService.deleteByJobId(jobId);
        jobRepo.deleteById(jobId);
        cancelled.remove(jobId);
        liveStep.remove(jobId);
        livePhaseProgress.remove(jobId);
    }
}
