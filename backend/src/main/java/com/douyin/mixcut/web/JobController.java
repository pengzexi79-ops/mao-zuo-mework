package com.douyin.mixcut.web;

import com.douyin.mixcut.domain.Job;
import com.douyin.mixcut.domain.JobOutput;
import com.douyin.mixcut.domain.OutputRepair;
import com.douyin.mixcut.domain.OutputVersion;
import com.douyin.mixcut.repository.Repositories.JobOutputRepo;
import com.douyin.mixcut.repository.Repositories.JobRepo;
import com.douyin.mixcut.service.DeliveryRepairService;
import com.douyin.mixcut.service.JobService;
import com.douyin.mixcut.service.OutputEditorService;
import com.douyin.mixcut.service.RenderPreparationService;
import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.external.FfmpegTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 批量出片：提交、进度、成片列表、下载。 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final RenderPreparationService renderPreparationService;
    @Autowired private OutputEditorService outputEditorService;
    private final JobRepo jobRepo;
    private final JobOutputRepo outputRepo;
    private final DeliveryRepairService deliveryRepairService;
    private final AppProps props;
    private final FfmpegTool ffmpeg;
    private final ObjectMapper om = new ObjectMapper();

    @Data
    public static class SubmitReq {
        private Long workflowId;
        private Long projectId;
        private Integer count = 1;
        private String name;
        /** 单任务总时限（秒）；留空或 0 使用 app.job-timeout-sec。 */
        private Integer timeoutSec;
        /** 无活动判定为僵死的时限（秒）；留空或 0 使用 app.job-stale-after-sec。 */
        private Integer staleAfterSec;
        /** 默认持续出片；固定数量必须由客户端显式选择。 */
        private Boolean continuous = true;
        /** 出片参数，原样透传，未出现的键沿用项目默认值 */
        private JsonNode params;
    }

    @PostMapping
    public R<Job> submit(@RequestBody SubmitReq req) {
        int count = req.getCount() == null ? 1 : req.getCount();
        if (count < 1) return R.fail("数量至少为 1");
        if (count > 200) return R.fail("单次最多 200 条，请分批提交");
        String json = "{}";
        if (req.getParams() != null && !req.getParams().isNull()) {
            try {
                json = om.writeValueAsString(req.getParams());
            } catch (Exception ignore) {
            }
        }
        if (Boolean.TRUE.equals(req.getContinuous())) {
            try {
                var root = om.readTree(json);
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("continuous", true);
                json = om.writeValueAsString(root);
                count = 1;
            } catch (Exception e) {
                return R.fail("连续出片参数初始化失败");
            }
        }
        return R.ok(jobService.submit(req.getWorkflowId(), req.getProjectId(), count, json, req.getName(),
                req.getTimeoutSec(), req.getStaleAfterSec()));
    }

    /**
     * 异步出片准备：持久化准备任务后立即返回 id/status，不阻塞请求线程等待公开素材抓取。
     * 快速路径（仅本地素材或本地素材已充足）会在响应前完成，返回完整的 done 快照；
     * 需要等待抓取队列时返回 running，请轮询 GET /api/jobs/prepare/{id}。
     */
    @PostMapping("/prepare")
    public R<RenderPreparationService.PrepareResult> prepare(@RequestBody RenderPreparationService.PrepareRequest req) {
        try {
            return R.ok(renderPreparationService.prepare(req));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage() == null ? "出片准备失败" : e.getMessage());
        }
    }

    /** 轮询出片准备进度：阶段、最终缺口、自动补齐结果与耗时状态。 */
    @GetMapping("/prepare/{id}")
    public R<RenderPreparationService.PrepareResult> prepareStatus(@PathVariable Long id) {
        try {
            return R.ok(renderPreparationService.status(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage() == null ? "准备任务不存在" : e.getMessage());
        }
    }

    /** 最近出片准备任务列表（新的在前），便于前端恢复轮询或排查。 */
    @GetMapping("/prepare")
    public R<List<RenderPreparationService.PrepareResult>> prepareList() {
        return R.ok(renderPreparationService.recent());
    }

    @GetMapping
    public R<List<Job>> list() {
        return R.ok(jobService.recent());
    }

    @GetMapping("/{id:\\d+}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        Job job = jobRepo.findById(id).orElse(null);
        if (job == null) return R.fail("任务不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", job);
        String step = jobService.currentStep(id);
        m.put("step", step);
        int phaseProgress = jobService.currentPhaseProgress(id);
        m.put("phaseProgress", phaseProgress);
        long elapsedSec = job.getCreatedAt() == null ? 0
                : java.time.Duration.between(job.getCreatedAt(), java.time.LocalDateTime.now()).getSeconds();
        int current = job.getCurrent() == null ? 0 : job.getCurrent();
        int total = job.getTotal() == null || job.getTotal() < 1
                ? Math.max(1, job.getCount() == null ? 1 : job.getCount()) : job.getTotal();
        boolean continuous = job.isContinuous();
        int completedItems = Math.max(0, current);
        int remainingItems = Math.max(0, total - completedItems);
        int overallProgress = continuous ? 0 : Math.min(100, Math.max(0, job.getProgress() == null ? (int) Math.round(completedItems * 100.0 / Math.max(1, total)) : job.getProgress()));
        double itemsPerMinute = elapsedSec > 0 ? completedItems * 60.0 / elapsedSec : 0;
        long etaSec = continuous ? 0 : (completedItems > 0 ? Math.max(0, Math.round(remainingItems / (completedItems / (double) Math.max(1, elapsedSec)))) : 0);
        m.put("overallProgress", overallProgress);
        m.put("currentItemProgress", phaseProgress);
        m.put("completedItems", completedItems);
        m.put("totalItems", total);
        m.put("phaseLabel", step == null || step.isBlank() ? (job.getSummary() == null ? "等待调度" : job.getSummary()) : step);
        m.put("isContinuous", continuous);
        m.put("elapsedSec", elapsedSec);
        m.put("itemsPerMinute", Math.round(itemsPerMinute * 100.0) / 100.0);
        m.put("etaSec", etaSec);
        m.put("lastHeartbeatAt", job.getLastActivityAt());
        m.put("outputs", jobService.outputs(id));
        return R.ok(m);
    }

    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        jobService.cancel(id);
        return R.ok();
    }

    @PostMapping("/{id}/pause")
    public R<Void> pause(@PathVariable Long id) {
        jobService.pause(id);
        return R.ok();
    }

    @PostMapping("/{id}/resume")
    public R<Void> resume(@PathVariable Long id) {
        jobService.resume(id);
        return R.ok();
    }

    @PostMapping("/{id}/retry-failed")
    public R<Void> retryFailed(@PathVariable Long id) {
        jobService.retryFailedItems(id);
        return R.ok();
    }

    @GetMapping("/{id}/outputs/{idx}/repair")
    public R<Map<String, Object>> repairDetail(@PathVariable Long id, @PathVariable int idx) {
        if (jobRepo.findById(id).isEmpty()) return R.fail("任务不存在");
        Map<String, Object> detail = new LinkedHashMap<>();
        List<OutputVersion> versions = jobService.outputVersions(id, idx);
        List<OutputRepair> repairs = jobService.outputRepairs(id, idx);
        detail.put("versions", versions.stream().map(version -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", version.getId());
            row.put("versionNo", version.getVersionNo());
            row.put("status", version.getStatus());
            row.put("durationSec", version.getDurationSec());
            row.put("repairStrategy", version.getRepairStrategy());
            row.put("qcReport", version.getQcReport());
            row.put("error", version.getError());
            row.put("createdAt", version.getCreatedAt());
            return row;
        }).toList());
        detail.put("repairs", repairs);
        detail.put("readableBgms", deliveryRepairService.readableBgms().stream().map(material -> {
            Map<String, Object> bgm = new LinkedHashMap<>();
            bgm.put("id", material.getId());
            bgm.put("name", material.getName());
            bgm.put("durationSec", material.getDurationSec());
            return bgm;
        }).toList());
        detail.put("finalOutput", outputRepo.findByJobIdAndIdx(id, idx).orElse(null));
        return R.ok(detail);
    }

    @Data
    public static class RepairDecisionReq {
        private String action;
        private Long bgmMaterialId;
    }

    @GetMapping("/{id}/outputs/{idx}/editor")
    public R<OutputEditorService.EditorState> editorState(@PathVariable Long id, @PathVariable int idx) {
        if (jobRepo.findById(id).isEmpty()) return R.fail("任务不存在");
        try { return R.ok(outputEditorService.open(id, idx)); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @PutMapping("/{id}/outputs/{idx}/editor/{sessionId}")
    public R<OutputEditorService.EditorState> saveEditor(@PathVariable Long id, @PathVariable int idx,
                                                          @PathVariable Long sessionId,
                                                          @RequestBody OutputEditorService.EditRequest request) {
        try {
            outputEditorService.verifySession(sessionId, id, idx);
            return R.ok(outputEditorService.save(sessionId, request));
        } catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @PostMapping("/{id}/outputs/{idx}/editor/{sessionId}/render")
    public R<OutputEditorService.EditorState> renderEditor(@PathVariable Long id, @PathVariable int idx,
                                                            @PathVariable Long sessionId) {
        try {
            outputEditorService.verifySession(sessionId, id, idx);
            return R.ok(outputEditorService.render(sessionId));
        } catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Data
    public static class EditorApplyReq { private Boolean confirm = false; }

    @PostMapping("/{id}/outputs/{idx}/editor/{sessionId}/apply")
    public R<OutputEditorService.EditorState> applyEditor(@PathVariable Long id, @PathVariable int idx,
                                                           @PathVariable Long sessionId,
                                                           @RequestBody EditorApplyReq request) {
        try {
            outputEditorService.verifySession(sessionId, id, idx);
            return R.ok(outputEditorService.apply(sessionId, request != null && Boolean.TRUE.equals(request.getConfirm())));
        } catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @PostMapping("/{id}/outputs/{idx}/repair-decision")
    public R<Void> repairDecision(@PathVariable Long id, @PathVariable int idx, @RequestBody RepairDecisionReq req) {
        if (req == null || req.getAction() == null || req.getAction().isBlank()) return R.fail("请选择修复动作");
        jobService.applyRepairDecision(id, idx, req.getAction(), req.getBgmMaterialId());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        try { jobService.deleteJob(id); return R.ok(); }
        catch (IllegalArgumentException e) { return R.fail(e.getMessage()); }
    }

    @Data
    public static class BatchDeleteReq { private List<Long> ids; }

    @PostMapping("/batch-delete")
    public R<Map<String, Object>> batchDelete(@RequestBody BatchDeleteReq req) {
        List<Long> ids = req == null || req.getIds() == null ? List.of() : req.getIds();
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : ids) if (id != null && id > 0) uniqueIds.add(id);
        if (uniqueIds.isEmpty()) return R.fail("请选择要删除的任务");
        if (uniqueIds.size() > 100) return R.fail("一次最多删除 100 条任务");

        int deleted = 0;
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long id : uniqueIds) {
            try {
                if (jobRepo.findById(id).isEmpty()) {
                    skipped.add(Map.of("id", id, "reason", "任务不存在或已删除"));
                    continue;
                }
                jobService.deleteJob(id);
                deleted++;
            } catch (IllegalArgumentException e) {
                skipped.add(Map.of("id", id, "reason", safeDeleteMessage(e)));
            } catch (RuntimeException e) {
                // 未知运行时异常只记录为跳过项,不中断整批删除
                skipped.add(Map.of("id", id, "reason", "任务删除失败"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("skipped", skipped);
        return R.ok(result);
    }

    private String safeDeleteMessage(IllegalArgumentException error) {
        String message = error.getMessage();
        return message == null || message.length() > 160 ? "任务当前不可删除" : message;
    }

    @PostMapping("/cleanup")
    public R<Integer> cleanupTerminal() {
        return R.ok(jobService.cleanupTerminal());
    }

    @GetMapping("/outputs/all")
    public R<List<JobOutput>> allOutputs() {
        return R.ok(jobService.allOutputs());
    }

    /** 只读预览输出目录中尚未建立记录的 MP4 文件，不自动写库或删除任何文件。 */
    @GetMapping("/outputs/reindex-candidates")
    public R<List<Map<String, Object>>> reindexCandidates() {
        List<String> indexed = outputRepo.findTop200ByOrderByIdDesc().stream()
                .map(JobOutput::getFilePath).filter(java.util.Objects::nonNull).toList();
        try (var files = Files.list(props.output())) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().toLowerCase().endsWith(".mp4")).toList()) {
                String absolute = file.toAbsolutePath().toString();
                if (indexed.contains(absolute)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("filePath", absolute);
                row.put("name", file.getFileName().toString());
                row.put("sizeBytes", Files.size(file));
                try {
                    double duration = ffmpeg.probe(absolute).getDuration();
                    row.put("durationSec", duration);
                    row.put("eligible", duration > 0 && duration <= 600);
                    if (duration > 600) row.put("reason", "时长超过 10 分钟，疑似异常输出，不建议补录");
                    else if (duration <= 0) row.put("reason", "无法读取有效时长");
                } catch (Exception ignored) {
                    row.put("durationSec", null);
                    row.put("eligible", false);
                    row.put("reason", "媒体探测失败");
                }
                result.add(row);
            }
            return R.ok(result);
        } catch (Exception e) {
            return R.fail("扫描输出目录失败：" + e.getClass().getSimpleName());
        }
    }

    @Data
    public static class ReindexReq { private List<String> filePaths; }

    /** 用户确认后补录可读输出；未绑定原任务，使用 jobId=0 明确标识导入成片。 */
    @PostMapping("/outputs/reindex")
    public R<Integer> reindex(@RequestBody ReindexReq req) {
        if (req.getFilePaths() == null || req.getFilePaths().isEmpty()) return R.fail("请选择要补录的输出文件");
        if (req.getFilePaths().size() > 100) return R.fail("一次最多补录 100 个文件");
        int added = 0;
        Path root = props.output().toAbsolutePath().normalize();
        for (String value : req.getFilePaths()) {
            try {
                Path file = Path.of(value).toAbsolutePath().normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase().endsWith(".mp4")) continue;
                if (outputRepo.findTop200ByOrderByIdDesc().stream().anyMatch(row -> file.toString().equals(row.getFilePath()))) continue;
                double duration = ffmpeg.probe(file.toString()).getDuration();
                if (duration <= 0 || duration > 600) continue;
                JobOutput output = new JobOutput();
                output.setJobId(0L);
                output.setIdx(added);
                output.setFilePath(file.toString());
                output.setDurationSec(duration);
                outputRepo.save(output);
                added++;
            } catch (Exception ignored) {
                // A corrupt candidate is skipped; do not disclose local paths in API errors.
            }
        }
        return R.ok(added);
    }

    /** 下载成片（带原文件名） */
    @GetMapping("/outputs/{outputId}/download")
    public ResponseEntity<?> download(@PathVariable Long outputId) {
        JobOutput o = outputRepo.findById(outputId).orElse(null);
        if (o == null || o.getFilePath() == null) return ResponseEntity.notFound().build();
        File f = new File(o.getFilePath());
        if (!f.exists()) return ResponseEntity.notFound().build();
        String fn = URLEncoder.encode(f.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(f.length())
                .body(new FileSystemResource(f));
    }

    /** 删除单条成片（同时删磁盘文件） */
    @DeleteMapping("/outputs/{outputId}")
    public R<Void> deleteOutput(@PathVariable Long outputId) {
        JobOutput o = outputRepo.findById(outputId).orElse(null);
        if (o == null) return R.ok();
        try {
            if (o.getFilePath() != null) Files.deleteIfExists(Path.of(o.getFilePath()));
        } catch (Exception ignore) {
        }
        outputRepo.delete(o);
        return R.ok();
    }
}
