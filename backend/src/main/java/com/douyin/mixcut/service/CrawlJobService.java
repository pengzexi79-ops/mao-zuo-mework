package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.*;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.repository.Repositories.CrawlJobRepo;
import com.douyin.mixcut.repository.Repositories.CrawlTaskRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlJobService {
    private static final int MAX_ITEMS = 200;
    private final CrawlJobRepo jobRepo;
    private final CrawlTaskRepo taskRepo;
    private final CrawlerGateway crawler;
    private final MaterialService materialService;
    private final AppProps props;
    private final ObjectMapper om = new ObjectMapper();
    @Qualifier("crawlExecutor") private final Executor crawlExecutor;
    private final Set<Long> dispatched = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    @Transactional
    public CrawlJob submitVideos(List<String> urls, String role) { return submitVideos(urls, role, null); }

    @Transactional
    public CrawlJob submitVideos(List<String> urls, String role, Long folderId) {
        List<String> clean = urls == null ? List.of() : urls.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().limit(MAX_ITEMS).toList();
        if (clean.isEmpty()) throw new IllegalArgumentException("请至少填写一个公开链接");
        CrawlJob job = base("视频批量抓取", "video", role, clean.size());
        job.setParams(writeJson(Map.of("urls", clean, "folderId", folderId == null ? 0L : folderId)));
        CrawlJob saved = jobRepo.save(job);
        saveTasks(saved, clean, null);
        dispatchAfterCommit(saved.getId());
        return saved;
    }

    @Transactional
    public CrawlJob submitAudio(List<CrawlerGateway.RemoteItem> items, String role) { return submitAudio(items, role, null); }

    @Transactional
    public CrawlJob submitAudio(List<CrawlerGateway.RemoteItem> items, String role, Long folderId) {
        return submitRemoteItems(items, role, "audio", folderId);
    }

    @Transactional
    public CrawlJob submitVideoItems(List<CrawlerGateway.RemoteItem> items, String role) { return submitVideoItems(items, role, null); }

    @Transactional
    public CrawlJob submitVideoItems(List<CrawlerGateway.RemoteItem> items, String role, Long folderId) {
        return submitRemoteItems(items, role, "video", folderId);
    }

    private CrawlJob submitRemoteItems(List<CrawlerGateway.RemoteItem> items, String role, String type, Long folderId) {
        List<CrawlerGateway.RemoteItem> clean = new ArrayList<>();
        if (items != null) {
            for (CrawlerGateway.RemoteItem item : items) {
                if (item == null) continue;
                clean.add(crawler.validateRemoteItem(item, type));
                if (clean.size() >= MAX_ITEMS) break;
            }
        }
        if (clean.isEmpty()) throw new IllegalArgumentException("未选择可导入的公开" + ("audio".equals(type) ? "音频" : "视频") + "素材");
        if ("audio".equals(type) && !"bgm".equals(role) && !"voice".equals(role)) {
            throw new IllegalArgumentException("公开音频只能导入为背景音乐或人声口播");
        }
        CrawlJob job = base("公开" + ("audio".equals(type) ? "音频" : "视频") + "素材导入", "remote-" + type, role, clean.size());
        job.setParams(writeJson(Map.of("items", clean, "folderId", folderId == null ? 0L : folderId)));
        CrawlJob saved = jobRepo.save(job);
        saveTasks(saved, clean.stream().map(CrawlerGateway.RemoteItem::getDownloadUrl).toList(), clean.stream().map(CrawlerGateway.RemoteItem::getTitle).toList());
        dispatchAfterCommit(saved.getId());
        return saved;
    }

    private void dispatchAfterCommit(Long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { dispatch(id); } });
        } else dispatch(id);
    }

    private CrawlJob base(String name, String mode, String role, int total) {
        CrawlJob job = new CrawlJob(); job.setName(name); job.setMode(mode); job.setRole(role == null || role.isBlank() ? "body" : role); job.setTotal(total); job.setProgress(0); job.setCurrentItem(0); job.setStatus(JobStatus.pending.name()); job.setTimeoutSec(props.getJobTimeoutSec()); job.setStaleAfterSec(props.getJobStaleAfterSec()); return job;
    }
    private void saveTasks(CrawlJob job, List<String> urls, List<String> titles) { for (int i=0;i<urls.size();i++) { CrawlTask t=new CrawlTask(); t.setJobId(job.getId()); t.setIdx(i); t.setUrl(urls.get(i)); if(titles!=null)t.setTitle(titles.get(i)); taskRepo.save(t); } }
    private String writeJson(Object value) { try { return om.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("任务参数无法保存"); } }

    private void dispatch(Long id) { if (id == null || !dispatched.add(id)) return; try { crawlExecutor.execute(() -> { try { run(id); } finally { dispatched.remove(id); cancelled.remove(id); } }); } catch (RuntimeException e) { dispatched.remove(id); log.warn("crawl dispatch failed", e); } }

    void run(Long id) {
        CrawlJob job = jobRepo.findById(id).orElse(null); if(job == null || isTerminal(job.getStatus())) return;
        job.setStatus(JobStatus.running.name()); touch(job); jobRepo.save(job);
        List<CrawlTask> tasks = taskRepo.findByJobIdOrderByIdxAsc(id);
        for (CrawlTask task : tasks) {
            if (cancelled.contains(id) || isTerminal(job.getStatus())) break;
            if (JobStatus.done.name().equals(task.getStatus())) continue;
            if (crawlTimedOut(job)) {
                failRemaining(tasks, task, "采集任务超过总时限，已停止后续下载", "TIMEOUT");
                break;
            }
            task.setStatus(JobStatus.running.name()); task.setStartedAt(LocalDateTime.now()); taskRepo.save(task);
            try {
                CrawlerGateway.FetchResult result;
                if (job.getMode() != null && job.getMode().startsWith("remote-")) {
                    CrawlerGateway.RemoteItem item = findRemoteItem(job, task.getIdx());
                    result = crawler.fetchRemoteItem(item);
                } else result = crawler.fetchVideo(task.getUrl());
                if (!result.isOk()) {
                    task.setErrorCode(result.getErrorCode());
                    task.setSource(result.getSource());
                    task.setHttpStatus(result.getHttpStatus());
                    throw new IllegalArgumentException(result.getMessage());
                }
                Material material = materialService.registerDownloaded(result.getFilePath(), safeUrl(task.getUrl()), parseRole(job.getRole()));
                Long folderId = folderId(job);
                if (folderId != null) { material.setFolderId(folderId); material = materialService.save(material); }
                task.setStatus(JobStatus.done.name());
                task.setVia(result.getVia());
                task.setErrorCode(null);
                task.setHttpStatus(null);
                task.setMaterialId(material.getId());
                task.setMessage(material.getStatus() == Material.Status.failed
                        ? "已下载但未通过质量准入：" + admissionReason(material)
                        : "已入库：" + material.getName());
            } catch (Exception e) { task.setStatus(JobStatus.failed.name()); task.setMessage(concise(e)); }
            task.setFinishedAt(LocalDateTime.now()); taskRepo.save(task); update(job, tasks.size());
        }
        if (cancelled.contains(id)) job.setStatus(JobStatus.cancelled.name()); else { long failed=taskRepo.countByJobIdAndStatus(id, JobStatus.failed.name()); job.setStatus(failed == tasks.size() ? JobStatus.failed.name() : JobStatus.done.name()); }
        job.setSummary(summary(tasks)); job.setProgress(100); job.setCurrentItem(tasks.size()); touch(job); jobRepo.save(job);
    }

    private CrawlerGateway.RemoteItem findRemoteItem(CrawlJob job, int idx) { try { var root=om.readTree(job.getParams()); return om.treeToValue(root.path("items").get(idx), CrawlerGateway.RemoteItem.class); } catch(Exception e) { throw new IllegalArgumentException("公开素材任务参数损坏"); } }
    private Long folderId(CrawlJob job) { try { long id = om.readTree(job.getParams()).path("folderId").asLong(0); return id > 0 ? id : null; } catch (Exception ignored) { return null; } }
    private boolean crawlTimedOut(CrawlJob job) {
        int timeout = Math.max(60, job.getTimeoutSec() == null ? props.getJobTimeoutSec() : job.getTimeoutSec());
        LocalDateTime started = job.getCreatedAt() == null ? LocalDateTime.now() : job.getCreatedAt();
        return Duration.between(started, LocalDateTime.now()).getSeconds() >= timeout;
    }

    private void failRemaining(List<CrawlTask> tasks, CrawlTask from, String message, String code) {
        boolean start = false;
        for (CrawlTask task : tasks) {
            if (task == from) start = true;
            if (!start || JobStatus.done.name().equals(task.getStatus())) continue;
            task.setStatus(JobStatus.failed.name());
            task.setErrorCode(code);
            task.setMessage(message);
            task.setFinishedAt(LocalDateTime.now());
            taskRepo.save(task);
        }
    }

    private void update(CrawlJob job, int total) { int done=(int)(taskRepo.countByJobIdAndStatus(job.getId(), JobStatus.done.name()) + taskRepo.countByJobIdAndStatus(job.getId(), JobStatus.failed.name())); job.setCurrentItem(done); job.setProgress(Math.min(99, total == 0 ? 0 : done * 100 / total)); touch(job); jobRepo.save(job); }
    private String summary(List<CrawlTask> tasks) {
        long downloaded = tasks.stream().filter(t -> JobStatus.done.name().equals(t.getStatus())).count();
        long admissionFailed = tasks.stream().filter(t -> JobStatus.done.name().equals(t.getStatus())
                && t.getMessage() != null && t.getMessage().startsWith("已下载但未通过质量准入")).count();
        long admitted = downloaded - admissionFailed;
        long failed = tasks.size() - downloaded;
        return "已下载 " + downloaded + " 条，准入可用 " + admitted + " 条，未通过准入 " + admissionFailed + " 条，下载失败 " + failed + " 条";
    }
    private String admissionReason(Material material) {
        if (material.getDurationSec() == null || material.getDurationSec() <= 0) {
            return "媒体探测失败或时长为零";
        }
        String tags = material.getTags() == null ? "" : material.getTags();
        return java.util.Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> tag.startsWith("低质:"))
                .map(tag -> tag.substring("低质:".length()))
                .findFirst()
                .orElse("质量准入未通过");
    }
    private void touch(CrawlJob job) { job.setLastActivityAt(LocalDateTime.now()); }
    private boolean isTerminal(String s) { return JobStatus.done.name().equals(s)||JobStatus.failed.name().equals(s)||JobStatus.cancelled.name().equals(s); }
    public void cancel(Long id) { jobRepo.findById(id).ifPresent(j->{ if(!isTerminal(j.getStatus())) { cancelled.add(id); j.setStatus(JobStatus.cancelled.name()); j.setSummary("已取消；当前下载结束后不会继续下一条"); touch(j); jobRepo.save(j); }}); }

    @Transactional
    public CrawlJob retryFailed(Long id) {
        CrawlJob job = detail(id);
        if (JobStatus.running.name().equals(job.getStatus())) throw new IllegalArgumentException("任务仍在执行中，请稍候");
        cancelled.remove(id);
        List<CrawlTask> tasks = taskRepo.findByJobIdOrderByIdxAsc(id);
        int pending = 0;
        for (CrawlTask task : tasks) {
            if (JobStatus.failed.name().equals(task.getStatus())
                    && !"URL_GUARD_REJECTED".equals(task.getErrorCode())) {
                task.setStatus(JobStatus.pending.name());
                // Preserve the latest diagnostic fields so a retried task remains explainable until it completes.
                task.setMessage("正在重试；上次失败：" + (task.getMessage() == null ? "未知原因" : task.getMessage()));
                task.setVia(null);
                task.setStartedAt(null);
                task.setFinishedAt(null);
                taskRepo.save(task);
                pending++;
            }
        }
        if (pending == 0) throw new IllegalArgumentException("没有可重试的失败项");
        job.setStatus(JobStatus.pending.name()); job.setError(null); job.setSummary("已重新排队 " + pending + " 条失败项");
        long completed = tasks.stream().filter(t -> JobStatus.done.name().equals(t.getStatus())).count();
        job.setCurrentItem((int) completed); job.setProgress(job.getTotal() == null || job.getTotal() == 0 ? 0 : (int) (completed * 100 / job.getTotal())); touch(job);
        CrawlJob saved = jobRepo.save(job); dispatchAfterCommit(id); return saved;
    }

    @Scheduled(fixedDelayString = "${app.job-watchdog-delay-ms:30000}")
    public void recoverPending() {
        try {
            jobRepo.findByStatusOrderByIdAsc(JobStatus.pending.name()).forEach(j -> dispatch(j.getId()));
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Setup mode: MySQL is not ready yet; avoid an error every scheduler tick.
        }
    }

    /** 崩溃后遗留的 running 采集任务重新排队，保留已完成 task 并跳过它们。 */
    @Scheduled(fixedDelayString = "${app.job-watchdog-delay-ms:30000}")
    @Transactional
    public void recoverStaleRunning() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (CrawlJob job : jobRepo.findByStatusOrderByIdAsc(JobStatus.running.name())) {
                int staleAfter = Math.max(60, job.getStaleAfterSec() == null ? props.getJobStaleAfterSec() : job.getStaleAfterSec());
                LocalDateTime activity = job.getLastActivityAt() == null ? now : job.getLastActivityAt();
                if (Duration.between(activity, now).getSeconds() < staleAfter || dispatched.contains(job.getId())) continue;
                for (CrawlTask task : taskRepo.findByJobIdOrderByIdxAsc(job.getId())) {
                    if (JobStatus.running.name().equals(task.getStatus())) {
                        task.setStatus(JobStatus.pending.name());
                        task.setMessage("服务中断后等待恢复");
                        task.setStartedAt(null);
                        taskRepo.save(task);
                    }
                }
                job.setStatus(JobStatus.pending.name());
                job.setSummary("检测到中断，已恢复到等待队列");
                touch(job);
                jobRepo.save(job);
                dispatch(job.getId());
            }
        } catch (org.springframework.dao.DataAccessException ignored) {
            // Setup mode: no crawl state exists until MySQL credentials are configured.
        }
    }

    @Transactional
    public void deleteJob(Long id) {
        CrawlJob job = detail(id);
        if (!isTerminal(job.getStatus())) throw new IllegalArgumentException("运行中的采集任务请先取消，再删除记录");
        taskRepo.deleteByJobId(id);
        jobRepo.deleteById(id);
        cancelled.remove(id);
        dispatched.remove(id);
    }

    @Transactional
    public int cleanupTerminal() {
        int deleted = 0;
        List<CrawlJob> terminalJobs = new ArrayList<>();
        terminalJobs.addAll(jobRepo.findByStatusOrderByIdAsc(JobStatus.done.name()));
        terminalJobs.addAll(jobRepo.findByStatusOrderByIdAsc(JobStatus.failed.name()));
        terminalJobs.addAll(jobRepo.findByStatusOrderByIdAsc(JobStatus.cancelled.name()));
        for (CrawlJob job : terminalJobs) {
            if (isTerminal(job.getStatus())) {
                taskRepo.deleteByJobId(job.getId());
                jobRepo.deleteById(job.getId());
                cancelled.remove(job.getId());
                dispatched.remove(job.getId());
                deleted++;
            }
        }
        return deleted;
    }

    public List<CrawlJob> recent() { return jobRepo.findTop50ByOrderByIdDesc(); }
    public CrawlJob detail(Long id) { return jobRepo.findById(id).orElseThrow(()->new IllegalArgumentException("采集任务不存在")); }
    public List<CrawlTask> tasks(Long id) { return taskRepo.findByJobIdOrderByIdxAsc(id); }
    private MaterialRole parseRole(String s) { try{return MaterialRole.valueOf(s);}catch(Exception e){return MaterialRole.body;} }
    private String safeUrl(String value) { if(value==null)return null; try { var u=java.net.URI.create(value); return new java.net.URI(u.getScheme(),null,u.getHost(),u.getPort(),u.getPath(),null,null).toString(); } catch(Exception e){return "[invalid URL]";} }
    private String concise(Exception e) { String s=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); return s.length()>1000?s.substring(0,1000):s; }
}
