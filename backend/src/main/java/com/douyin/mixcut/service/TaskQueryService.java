package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.*;
import com.douyin.mixcut.dto.UnifiedTask;
import com.douyin.mixcut.repository.Repositories.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only aggregation of persisted task records. It does not trigger work or network calls. */
@Service
@RequiredArgsConstructor
public class TaskQueryService {
    private final MediaTaskRepo mediaTasks;
    private final MediaGenerationTaskRepo generationTasks;
    private final CrawlJobRepo crawlJobs;
    private final PreparationTaskRepo preparations;
    private final JobRepo jobs;

    public List<UnifiedTask> list(Integer requestedLimit, String source, String status) {
        int limit = Math.max(1, Math.min(200, requestedLimit == null ? 50 : requestedLimit));
        List<UnifiedTask> all = new ArrayList<>();
        if (source == null || source.isBlank() || "media".equals(source)) mediaTasks.findTop50ByOrderByIdDesc().stream().map(this::media).forEach(all::add);
        if (source == null || source.isBlank() || "ai-generation".equals(source)) generationTasks.findTop50ByOrderByIdDesc().stream().map(this::generation).forEach(all::add);
        if (source == null || source.isBlank() || "crawl".equals(source)) crawlJobs.findTop50ByOrderByIdDesc().stream().map(this::crawl).forEach(all::add);
        if (source == null || source.isBlank() || "preparation".equals(source)) preparations.findTop20ByOrderByIdDesc().stream().map(this::preparation).forEach(all::add);
        if (source == null || source.isBlank() || "render".equals(source)) jobs.findTop100ByOrderByIdDesc().stream().map(this::render).forEach(all::add);
        return all.stream().filter(task -> status == null || status.isBlank() || status.equals(task.getRawStatus()))
                .sorted(Comparator.comparing(TaskQueryService::time).reversed().thenComparing(UnifiedTask::getId).reversed())
                .limit(limit).toList();
    }

    private UnifiedTask media(MediaTask item) {
        UnifiedTask result = task(item.getTaskKey(), "media", item.getKind(), item.getStatus(), item.getProgress(), item.getKind(), first(item.getMessage(), item.getError()), item.getCreatedAt(), item.getUpdatedAt(), List.of("pending", "running").contains(item.getStatus()), "failed".equals(item.getStatus()));
        result.setPhase(item.getPhase());
        result.setHeartbeatAt(item.getLastActivityAt() == null ? null : item.getLastActivityAt().toString());
        result.setTimeoutSec(item.getTimeoutSec());
        result.setStaleAfterSec(item.getStaleAfterSec());
        result.setRecoveryState(item.getRecoveryState());
        result.setRecoveryReason(item.getRecoveryReason());
        result.setErrorCode(item.getErrorCode());
        result.setRetryCount(item.getRetryCount());
        return result;
    }
    private UnifiedTask generation(MediaGenerationTask item) {
        return task(item.getTaskKey(), "ai-generation", item.getKind(), item.getStatus(), item.getProgress(), item.getModel(), first(item.getMessage(), item.getError()), item.getCreatedAt(), item.getUpdatedAt(), false, "failed".equals(item.getStatus()) || "manual_review".equals(item.getStatus()));
    }
    private UnifiedTask crawl(CrawlJob item) {
        return task("crawl-" + item.getId(), "crawl", item.getMode(), item.getStatus(), item.getProgress(), item.getName(), first(item.getSummary(), item.getError()), item.getCreatedAt(), item.getUpdatedAt(), "running".equals(item.getStatus()) || "pending".equals(item.getStatus()), "failed".equals(item.getStatus()));
    }
    private UnifiedTask preparation(PreparationTask item) {
        int progress = "done".equals(item.getStatus()) || "timedout".equals(item.getStatus()) || "failed".equals(item.getStatus()) ? 100 : 0;
        return task("preparation-" + item.getId(), "preparation", "preparation", item.getStatus(), progress, "出片准备", first(item.getError(), item.getStatus()), item.getCreatedAt(), item.getUpdatedAt(), false, "failed".equals(item.getStatus()) || "timedout".equals(item.getStatus()));
    }
    private UnifiedTask render(Job item) {
        return task("render-" + item.getId(), "render", "render", item.getStatus(), item.getProgress(), first(item.getCurrentStep(), item.getSummary()), first(item.getSummary(), item.getError()), item.getCreatedAt(), item.getUpdatedAt(), List.of("running", "pending", "paused").contains(item.getStatus()), List.of("failed", "awaiting_decision").contains(item.getStatus()));
    }
    private UnifiedTask task(String id, String source, String type, String status, Integer progress, String label, String message, LocalDateTime created, LocalDateTime updated, boolean cancel, boolean retry) {
        UnifiedTask result = new UnifiedTask(); result.setId(id); result.setSource(source); result.setType(type); result.setRawStatus(status); result.setPhase(status); result.setProgress(Math.max(0, Math.min(100, progress == null ? 0 : progress))); result.setLabel(label); result.setMessage(message); result.setCreatedAt(created == null ? null : created.toString()); result.setUpdatedAt(updated == null ? null : updated.toString()); result.setCanCancel(cancel); result.setCanRetry(retry); return result;
    }
    private String first(String primary, String fallback) { return primary == null || primary.isBlank() ? fallback : primary; }
    private static LocalDateTime time(UnifiedTask task) { try { return task.getUpdatedAt() == null ? LocalDateTime.MIN : LocalDateTime.parse(task.getUpdatedAt()); } catch (Exception e) { return LocalDateTime.MIN; } }
}
