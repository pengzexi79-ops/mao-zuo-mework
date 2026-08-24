package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.MediaTask;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.external.ProcessRegistry;
import com.douyin.mixcut.external.TaskAwareProcRunner;
import com.douyin.mixcut.repository.Repositories.MediaTaskRepo;
import com.douyin.mixcut.repository.MaterialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded local media operations exposed to the workbench. */
@Slf4j
@Service
public class MediaToolsService {
    private final AppProps props;
    private final ProcRunner runner;
    private final MaterialStore materials;
    private final MaterialService materialService;
    private final AudioEngineService audioEngine;
    private final FfmpegTool ffmpeg;
    private final MaterialDeleteService materialDeleteService;
    private final MediaTaskRepo mediaTaskRepo;
    private final ProcessRegistry processRegistry;
    private final TaskAwareProcRunner taskRunner;
    @Qualifier("mediaExecutor") private final Executor mediaExecutor;

    public MediaToolsService(AppProps props, ProcRunner runner, MaterialStore materials,
                             MaterialService materialService, AudioEngineService audioEngine, FfmpegTool ffmpeg,
                             MaterialDeleteService materialDeleteService, MediaTaskRepo mediaTaskRepo,
                             ProcessRegistry processRegistry, Executor mediaExecutor) {
        this(props, runner, materials, materialService, audioEngine, ffmpeg, materialDeleteService,
                mediaTaskRepo, processRegistry, null, mediaExecutor);
    }

    @Autowired
    public MediaToolsService(AppProps props, ProcRunner runner, MaterialStore materials,
                             MaterialService materialService, AudioEngineService audioEngine, FfmpegTool ffmpeg,
                             MaterialDeleteService materialDeleteService, MediaTaskRepo mediaTaskRepo,
                             ProcessRegistry processRegistry, TaskAwareProcRunner taskRunner,
                             @Qualifier("mediaExecutor") Executor mediaExecutor) {
        this.props = props;
        this.runner = runner;
        this.materials = materials;
        this.materialService = materialService;
        this.audioEngine = audioEngine;
        this.ffmpeg = ffmpeg;
        this.materialDeleteService = materialDeleteService;
        this.mediaTaskRepo = mediaTaskRepo;
        this.processRegistry = processRegistry;
        this.taskRunner = taskRunner;
        this.mediaExecutor = mediaExecutor;
    }
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final Set<String> startedTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, ProcessRegistry.CancellationContext> cancellationContexts = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class Task {
        private String id;
        private String kind;
        private String status = "pending";
        private String phase = "queued";
        private int progress;
        private String message;
        private String error;
        private String errorCode;
        private String recoveryState = "none";
        private String recoveryReason;
        private String heartbeatAt;
        private int timeoutSec;
        private int staleAfterSec;
        private int retryCount;
        private String engine;
        private String outputDirectory;
        private List<String> resultPaths = List.of();
        private List<MaterialResult> results = List.of();
        private List<Material> materials = List.of();
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = createdAt;
    }

    @Data
    public static class MaterialResult {
        private Long materialId;
        private String name;
        private String fileType;
        private String filePath;
        private String previewUrl;
        private boolean inLibrary;

        static MaterialResult from(Material material) {
            MaterialResult result = new MaterialResult();
            result.setMaterialId(material.getId());
            result.setName(material.getName());
            result.setFileType(material.getFileType() == null ? null : material.getFileType().name());
            result.setFilePath(material.getFilePath());
            result.setPreviewUrl(material.getPreviewUrl());
            result.setInLibrary(material.getId() != null);
            return result;
        }
    }

    @Data
    public static class ImageRequest {
        private Long materialId;
        private String operation = "resize";
        private Integer width;
        private Integer height;
        private Integer rotate;
        private Boolean flipHorizontal;
        private Boolean flipVertical;
        private Integer quality;
    }

    @Data
    public static class TimeRange {
        private Double start;
        private Double end;
    }

    @Data
    public static class CoverRequest {
        private Long materialId;
        private Integer x;
        private Integer y;
        private Integer width;
        private Integer height;
        private String color = "black@1.0";
        private Double start;
        private Double end;
    }

    /**
     * Browser submits only an existing material ID plus numeric edit intent. It never controls
     * an input/output path, FFmpeg argument, or raw filter expression.
     */
    @Data
    public static class TimelineRequest {
        private Long materialId;
        private Double sourceStart;
        private Double sourceEnd;
        private List<TimeRange> removeRanges = List.of();
        /** keep-original-audio | unmute | silent */
        private String audioMode = "keep-original-audio";
        /** library_keep_original | computer_only | library_delete_original */
        private String resultPolicy = "library_keep_original";
        /** Explicit user acknowledgement required for library_delete_original. */
        private Boolean deleteSourceConfirmed = false;
    }

    public Task image(ImageRequest request) {
        if (request == null || request.getMaterialId() == null) throw new IllegalArgumentException("请选择图片素材");
        Material source = materials.findById(request.getMaterialId()).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        if (source.getFileType() != Material.FileType.image) throw new IllegalArgumentException("图片工具只能处理图片素材");
        Path input = safeMaterialPath(source);
        String id = newTask("image", request);
        dispatch(id, () -> runImage(id, source, input, request, context(id)));
        return tasks.get(id);
    }

    public Task separate(Long materialId) {
        if (materialId == null) throw new IllegalArgumentException("请选择音视频素材");
        materials.findById(materialId).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        String id = newTask("audio-separate", Map.of("materialId", materialId));
        dispatch(id, () -> runSeparation(id, materialId, context(id)));
        return tasks.get(id);
    }

    public Task split(Long materialId, double clipSec) {
        if (materialId == null) throw new IllegalArgumentException("请选择视频素材");
        if (clipSec < 1 || clipSec > 15) throw new IllegalArgumentException("每段时长请设置在 1 到 15 秒之间");
        materials.findById(materialId).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        String id = newTask("video-split", Map.of("materialId", materialId, "clipSec", clipSec));
        dispatch(id, () -> runSplit(id, materialId, clipSec, context(id)));
        return tasks.get(id);
    }

    /** Creates a new image/video with a user-confirmed rectangular subtitle cover. */
    public Task cover(CoverRequest request) {
        if (request == null || request.getMaterialId() == null) throw new IllegalArgumentException("请选择图片或视频素材");
        Material source = materials.findById(request.getMaterialId()).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        if (source.getFileType() != Material.FileType.image && source.getFileType() != Material.FileType.video) throw new IllegalArgumentException("字幕遮盖只支持图片或视频");
        Path input = safeMaterialPath(source);
        FfmpegTool.MediaInfo info = ffmpeg.probe(input.toString());
        int x = request.getX() == null ? 0 : request.getX();
        int y = request.getY() == null ? 0 : request.getY();
        int width = request.getWidth() == null ? 0 : request.getWidth();
        int height = request.getHeight() == null ? 0 : request.getHeight();
        if (x < 0 || y < 0 || width < 1 || height < 1) throw new IllegalArgumentException("遮盖区域必须是有效的正数矩形");
        if (info.getWidth() > 0 && (x + width > info.getWidth() || y + height > info.getHeight())) throw new IllegalArgumentException("遮盖区域超出素材画面范围");
        double start = request.getStart() == null ? 0 : request.getStart();
        double end = request.getEnd() == null ? info.getDuration() : request.getEnd();
        if (source.getFileType() == Material.FileType.video && (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || end > info.getDuration() + 0.01)) throw new IllegalArgumentException("视频遮盖时间范围无效");
        if (source.getFileType() == Material.FileType.image) { start = 0; end = 1; }
        String id = newTask("subtitle-cover", request);
        final double rangeStart = start;
        final double rangeEnd = end;
        dispatch(id, () -> runCover(id, source, input, request, rangeStart, rangeEnd, context(id)));
        return tasks.get(id);
    }

    /** Creates a new video from source ranges after strict server-side range validation. */
    public Task timeline(TimelineRequest request) {
        if (request == null || request.getMaterialId() == null) throw new IllegalArgumentException("请选择视频素材");
        Material source = materials.findById(request.getMaterialId()).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        if (source.getFileType() != Material.FileType.video) throw new IllegalArgumentException("时间线编辑只能处理视频素材");
        String audioMode = request.getAudioMode() == null ? "keep-original-audio" : request.getAudioMode();
        if (!List.of("keep-original-audio", "unmute", "silent").contains(audioMode)) throw new IllegalArgumentException("不支持的音轨模式");
        String resultPolicy = request.getResultPolicy() == null ? "library_keep_original" : request.getResultPolicy();
        if (!List.of("library_keep_original", "computer_only", "library_delete_original").contains(resultPolicy)) throw new IllegalArgumentException("不支持的结果保存策略");
        if ("library_delete_original".equals(resultPolicy) && !Boolean.TRUE.equals(request.getDeleteSourceConfirmed())) {
            throw new IllegalArgumentException("删除原素材前必须明确勾选确认");
        }
        Path input = safeMaterialPath(source);
        FfmpegTool.MediaInfo info = ffmpeg.probe(input.toString());
        if (!info.isHasVideo() || info.getDuration() < 0.1) throw new IllegalArgumentException("无法读取视频流，请重新探测素材后再试");
        if ("unmute".equals(audioMode) && !info.isHasAudio()) throw new IllegalArgumentException("原视频没有可恢复的音轨，无法解除静音");
        validateTimelineRequest(request, info.getDuration());
        String id = newTask("video-timeline", request);
        dispatch(id, () -> runTimeline(id, source, input, request, info, context(id)));
        return tasks.get(id);
    }

    /** 智能剪除静音/废片区间：基于 Auto-Editor 自动剪辑，保留有效内容，原视频不覆盖。 */
    public Task trimSilence(Long materialId) {
        if (materialId == null) throw new IllegalArgumentException("请选择视频素材");
        materials.findById(materialId).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        String id = newTask("auto-trim", Map.of("materialId", materialId));
        dispatch(id, () -> runAutoTrim(id, materialId, context(id)));
        return tasks.get(id);
    }

    public Task cancel(String id) {
        MediaTask persisted = mediaTaskRepo.findByTaskKey(id).orElseThrow(() -> new IllegalArgumentException("媒体任务不存在"));
        if ("cancelled".equals(persisted.getStatus())) return fromPersisted(persisted);
        if ("done".equals(persisted.getStatus()) || "failed".equals(persisted.getStatus())) throw new IllegalArgumentException("任务已结束，不能取消");
        persisted.setStatus("cancelled");
        persisted.setPhase("finished");
        persisted.setErrorCode("MEDIA_CANCELLED");
        persisted.setMessage("已取消媒体任务");
        persisted.setLastActivityAt(LocalDateTime.now());
        mediaTaskRepo.save(persisted);
        ProcessRegistry.CancellationContext context = cancellationContexts.get(id);
        if (context != null) processRegistry.cancel(context);
        else processRegistry.cancel(id);
        Future<?> future = futures.remove(id);
        if (future != null) future.cancel(true);
        Task task = tasks.get(id);
        if (task != null) {
            task.setStatus("cancelled");
            task.setPhase("finished");
            task.setErrorCode("MEDIA_CANCELLED");
            task.setMessage("已取消媒体任务");
        }
        cleanupTaskOutputs(id);
        if (!startedTasks.contains(id)) releaseContext(id);
        return fromPersisted(persisted);
    }

    public Task get(String id) {
        Task task = tasks.get(id);
        if (task != null) return task;
        MediaTask persisted = mediaTaskRepo.findByTaskKey(id).orElseThrow(() -> new IllegalArgumentException("媒体任务不存在"));
        return fromPersisted(persisted);
    }

    public Task retry(String id) {
        MediaTask persisted = mediaTaskRepo.findByTaskKey(id).orElseThrow(() -> new IllegalArgumentException("媒体任务不存在"));
        if (!"failed".equals(persisted.getStatus())) throw new IllegalArgumentException("只有失败的媒体任务可以重试");
        int attempts = persisted.getRetryCount() == null ? 0 : persisted.getRetryCount();
        if (attempts >= 3) throw new IllegalArgumentException("媒体任务已达到最大重试次数");
        persisted.setStatus("pending");
        persisted.setPhase("queued");
        persisted.setRetryCount(attempts + 1);
        persisted.setError(null);
        persisted.setErrorCode(null);
        persisted.setRecoveryState("retrying");
        persisted.setRecoveryReason("用户发起失败重试");
        persisted.setMessage("失败重试已排队");
        persisted.setLastActivityAt(LocalDateTime.now());
        mediaTaskRepo.save(persisted);
        tasks.remove(id);
        cancellationContexts.remove(id);
        try { restoreAndDispatch(persisted); }
        catch (Exception error) {
            persisted.setStatus("failed");
            persisted.setPhase("finished");
            persisted.setRecoveryState("failed");
            persisted.setErrorCode("MEDIA_RECOVERY_FAILED");
            persisted.setError(safeMessage(error));
            persisted.setMessage("媒体任务重试恢复失败：" + safeMessage(error));
            mediaTaskRepo.save(persisted);
        }
        return fromPersisted(persisted);
    }

    public List<Task> recent() {
        return mediaTaskRepo.findTop50ByOrderByIdDesc().stream().map(this::fromPersisted).toList();
    }

    private Task fromPersisted(MediaTask persisted) {
        Task task = new Task();
        task.setId(persisted.getTaskKey());
        task.setKind(persisted.getKind());
        task.setStatus(persisted.getStatus());
        task.setPhase(persisted.getPhase() == null ? persisted.getStatus() : persisted.getPhase());
        task.setProgress(persisted.getProgress() == null ? 0 : persisted.getProgress());
        task.setEngine(persisted.getEngine());
        task.setMessage(persisted.getMessage());
        task.setError(persisted.getError());
        task.setErrorCode(persisted.getErrorCode());
        task.setRecoveryState(persisted.getRecoveryState() == null ? "none" : persisted.getRecoveryState());
        task.setRecoveryReason(persisted.getRecoveryReason());
        task.setHeartbeatAt(persisted.getLastActivityAt() == null ? null : persisted.getLastActivityAt().toString());
        task.setTimeoutSec(persisted.getTimeoutSec() == null ? 0 : persisted.getTimeoutSec());
        task.setStaleAfterSec(persisted.getStaleAfterSec() == null ? 0 : persisted.getStaleAfterSec());
        task.setRetryCount(persisted.getRetryCount() == null ? 0 : persisted.getRetryCount());
        task.setOutputDirectory(persisted.getOutputDirectory());
        task.setCreatedAt(persisted.getCreatedAt() == null ? System.currentTimeMillis() : persisted.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        task.setUpdatedAt(persisted.getUpdatedAt() == null ? task.getCreatedAt() : persisted.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        try {
            if (persisted.getResultPaths() != null) task.setResultPaths(objectMapper.readValue(persisted.getResultPaths(), List.class));
            if (persisted.getResults() != null) task.setResults(objectMapper.readValue(persisted.getResults(), objectMapper.getTypeFactory().constructCollectionType(List.class, MaterialResult.class)));
        } catch (Exception ignored) { }
        return task;
    }

    /** Opens only the application-managed output root; no browser-supplied path is accepted. */
    public void openOutputDirectory() {
        Path root = props.mediaToolsOutput().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalStateException("媒体工具输出目录不存在");
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            throw new IllegalStateException("当前系统不支持自动打开文件夹，请复制页面显示的路径");
        }
        try {
            new ProcessBuilder("explorer.exe", root.toString()).start();
        } catch (Exception e) {
            throw new IllegalStateException("无法打开媒体工具输出目录，请复制页面显示的路径");
        }
    }

    private String newTask(String kind) { return newTask(kind, null); }

    private String newTask(String kind, Object params) {
        Task task = new Task();
        task.setId(UUID.randomUUID().toString());
        task.setKind(kind);
        task.setOutputDirectory(props.mediaToolsOutput().toString());
        tasks.put(task.getId(), task);
        cancellationContexts.put(task.getId(), processRegistry.create(task.getId()));
        MediaTask persisted = new MediaTask();
        persisted.setTaskKey(task.getId());
        persisted.setKind(kind);
        persisted.setStatus("pending");
        persisted.setPhase("queued");
        persisted.setProgress(0);
        persisted.setTimeoutSec(1800);
        persisted.setStaleAfterSec(900);
        persisted.setRetryCount(0);
        persisted.setRecoveryState("none");
        task.setPhase("queued");
        task.setTimeoutSec(1800);
        task.setStaleAfterSec(900);
        task.setRetryCount(0);
        task.setRecoveryState("none");
        persisted.setOutputDirectory(task.getOutputDirectory());
        try { persisted.setParams(params == null ? null : objectMapper.writeValueAsString(params)); }
        catch (Exception error) { throw new IllegalArgumentException("媒体任务参数无法保存", error); }
        persisted.setLastActivityAt(LocalDateTime.now());
        try { mediaTaskRepo.save(persisted); } catch (Exception error) {
            log.warn("无法持久化媒体任务 {}：{}", task.getId(), error.toString());
        }
        return task.getId();
    }

    private void dispatch(String id, Runnable work) {
        FutureTask<Void> future = new FutureTask<>(() -> {
            try {
                checkpoint(id);
                startedTasks.add(id);
                update(tasks.get(id), "running", 1, "媒体任务已开始");
                work.run();
            } catch (java.util.concurrent.CancellationException cancelled) {
                markCancelled(id);
            } finally {
                futures.remove(id);
                startedTasks.remove(id);
                if (isTerminal(id)) {
                    MediaTask persisted = mediaTaskRepo.findByTaskKey(id).orElse(null);
                    if (persisted != null && ("failed".equals(persisted.getStatus()) || "cancelled".equals(persisted.getStatus()))) {
                        cleanupTaskOutputs(id);
                    }
                    releaseContext(id);
                }
            }
            return null;
        });
        futures.put(id, future);
        try {
            mediaExecutor.execute(future);
        } catch (RuntimeException error) {
            futures.remove(id, future);
            Task task = tasks.get(id);
            if (task != null) update(task, "pending", 0, "媒体任务等待执行器资源");
            log.warn("媒体任务 {} 未进入执行队列：{}", id, error.toString());
        }
    }

    private void restoreAndDispatch(MediaTask persisted) throws Exception {
        var root = objectMapper.readTree(persisted.getParams());
        String id = persisted.getTaskKey();
        Task task = fromPersisted(persisted);
        tasks.put(id, task);
        cancellationContexts.put(id, processRegistry.create(id));
        String kind = persisted.getKind();
        if ("image".equals(kind)) {
            ImageRequest request = objectMapper.treeToValue(root, ImageRequest.class);
            Material source = materials.findById(request.getMaterialId()).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
            dispatch(id, () -> runImage(id, source, safeMaterialPath(source), request, context(id)));
        } else if ("audio-separate".equals(kind)) {
            Long materialId = root.path("materialId").asLong(0);
            dispatch(id, () -> runSeparation(id, materialId, context(id)));
        } else if ("video-split".equals(kind)) {
            dispatch(id, () -> runSplit(id, root.path("materialId").asLong(0), root.path("clipSec").asDouble(3), context(id)));
        } else if ("subtitle-cover".equals(kind)) {
            CoverRequest request = objectMapper.treeToValue(root, CoverRequest.class);
            Material source = materials.findById(request.getMaterialId()).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
            FfmpegTool.MediaInfo info = ffmpeg.probe(safeMaterialPath(source).toString());
            dispatch(id, () -> runCover(id, source, safeMaterialPath(source), request,
                    source.getFileType() == Material.FileType.image ? 0 : request.getStart() == null ? 0 : request.getStart(),
                    source.getFileType() == Material.FileType.image ? 1 : request.getEnd() == null ? info.getDuration() : request.getEnd(),
                    context(id)));
        } else if ("video-timeline".equals(kind)) {
            TimelineRequest request = objectMapper.treeToValue(root, TimelineRequest.class);
            Material source = materials.findById(request.getMaterialId()).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
            Path input = safeMaterialPath(source);
            FfmpegTool.MediaInfo info = ffmpeg.probe(input.toString());
            dispatch(id, () -> runTimeline(id, source, input, request, info, context(id)));
        } else if ("auto-trim".equals(kind)) {
            dispatch(id, () -> runAutoTrim(id, root.path("materialId").asLong(0), context(id)));
        } else throw new IllegalArgumentException("不支持恢复的媒体任务类型");
    }

    private String safeMessage(Exception error) {
        String value = error == null ? "未知原因" : error.getMessage();
        if (value == null || value.isBlank()) return "未知原因";
        return value.length() > 400 ? value.substring(0, 400) : value;
    }

    private ProcessRegistry.CancellationContext context(String id) {
        return cancellationContexts.computeIfAbsent(id, processRegistry::create);
    }

    private void checkpoint(String id) {
        ProcessRegistry.CancellationContext context = context(id);
        context.throwIfCancelled();
        MediaTask persisted = mediaTaskRepo.findByTaskKey(id).orElse(null);
        if (persisted != null && "cancelled".equals(persisted.getStatus())) {
            processRegistry.cancel(context);
            context.throwIfCancelled();
        }
    }

    private boolean isCancelled(String id) {
        ProcessRegistry.CancellationContext context = cancellationContexts.get(id);
        if (context != null && context.isCancelled()) return true;
        return mediaTaskRepo.findByTaskKey(id).map(task -> "cancelled".equals(task.getStatus())).orElse(false);
    }

    private void markCancelled(String id) {
        Task task = tasks.get(id);
        if (task != null) {
            task.setStatus("cancelled");
            task.setMessage("已取消媒体任务");
            task.setUpdatedAt(System.currentTimeMillis());
        }
        cleanupTaskOutputs(id);
        persistCancelled(id);
    }

    private void persistCancelled(String id) {
        mediaTaskRepo.findByTaskKey(id).ifPresent(task -> {
            if (!"cancelled".equals(task.getStatus())) {
                task.setStatus("cancelled");
                task.setMessage("已取消媒体任务");
                task.setLastActivityAt(LocalDateTime.now());
                mediaTaskRepo.save(task);
            }
        });
    }

    private boolean isTerminal(String id) {
        return mediaTaskRepo.findByTaskKey(id).map(task ->
                "done".equals(task.getStatus()) || "failed".equals(task.getStatus()) || "cancelled".equals(task.getStatus())
        ).orElse(true);
    }

    private void releaseContext(String id) {
        ProcessRegistry.CancellationContext context = cancellationContexts.remove(id);
        if (context != null) processRegistry.forget(context);
    }

    private void cleanupTaskOutputs(String id) {
        ProcessRegistry.CancellationContext context = cancellationContexts.get(id);
        if (context != null) processRegistry.cleanupOutputs(context);
    }

    private void registerOutput(String id, Path output) {
        ProcessRegistry.CancellationContext context = cancellationContexts.get(id);
        if (context != null) processRegistry.registerOutput(context, output, props.mediaToolsOutput());
    }

    private void persist(String id) {
        MediaTask task = mediaTaskRepo.findByTaskKey(id).orElse(null);
        Task snapshot = tasks.get(id);
        if (task == null || snapshot == null) return;
        if ("cancelled".equals(task.getStatus()) && !"cancelled".equals(snapshot.getStatus())) return;
        task.setStatus(snapshot.getStatus());
        task.setPhase(snapshot.getPhase());
        task.setProgress(snapshot.getProgress());
        task.setMessage(snapshot.getMessage());
        task.setError(snapshot.getError());
        task.setErrorCode(snapshot.getErrorCode());
        task.setRecoveryState(snapshot.getRecoveryState());
        task.setRecoveryReason(snapshot.getRecoveryReason());
        task.setEngine(snapshot.getEngine());
        task.setOutputDirectory(snapshot.getOutputDirectory());
        try {
            task.setResultPaths(objectMapper.writeValueAsString(snapshot.getResultPaths()));
            task.setResults(objectMapper.writeValueAsString(snapshot.getResults()));
        } catch (Exception ignored) { }
        task.setLastActivityAt(LocalDateTime.now());
        try { mediaTaskRepo.save(task); } catch (Exception error) {
            log.warn("无法更新媒体任务 {}：{}", id, error.toString());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverMediaTasksAtStartup() { recoverMediaTasks(); }

    @Scheduled(fixedDelayString = "${app.job-watchdog-delay-ms:30000}")
    public void recoverMediaTasks() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (MediaTask persisted : mediaTaskRepo.findByStatusOrderByIdAsc("running")) {
                int staleAfter = Math.max(1, persisted.getStaleAfterSec() == null ? 900 : persisted.getStaleAfterSec());
                LocalDateTime cutoff = now.minusSeconds(staleAfter);
                if (persisted.getLastActivityAt() != null && persisted.getLastActivityAt().isAfter(cutoff)) continue;
                int claimed = mediaTaskRepo.claimStaleForRecovery(persisted.getTaskKey(), cutoff,
                        "超过任务心跳阈值 " + staleAfter + " 秒", now);
                if (claimed != 1) continue;
                persisted.setStatus("pending");
                persisted.setPhase("recovering");
                persisted.setRetryCount((persisted.getRetryCount() == null ? 0 : persisted.getRetryCount()) + 1);
                persisted.setRecoveryState("requeued");
                persisted.setRecoveryReason("超过任务心跳阈值 " + staleAfter + " 秒");
                persisted.setMessage("后端服务中断，媒体任务已重新排队");
                persisted.setLastActivityAt(now);
                mediaTaskRepo.save(persisted);
            }
            for (MediaTask persisted : mediaTaskRepo.findByStatusOrderByIdAsc("pending")) {
                if (tasks.containsKey(persisted.getTaskKey())) continue;
                try { restoreAndDispatch(persisted); }
                catch (Exception error) {
                    persisted.setStatus("failed");
                    persisted.setPhase("finished");
                    persisted.setRecoveryState("failed");
                    persisted.setRecoveryReason(safeMessage(error));
                    persisted.setErrorCode("MEDIA_RECOVERY_FAILED");
                    persisted.setMessage("媒体任务恢复失败：" + safeMessage(error));
                    persisted.setError(safeMessage(error));
                    persisted.setLastActivityAt(LocalDateTime.now());
                    mediaTaskRepo.save(persisted);
                }
            }
        } catch (Exception error) {
            log.debug("媒体任务恢复暂不可用：{}", error.toString());
        }
    }

    private void runImage(String id, Material source, Path input, ImageRequest request,
                          ProcessRegistry.CancellationContext cancellation) {
        Task task = tasks.get(id);
        try {
            cancellation.throwIfCancelled();
            update(task, "running", 10, "正在准备图片处理");
            Path dir = props.mediaToolsOutput().resolve("generated-images");
            Files.createDirectories(dir);
            String ext = "png";
            Path output = dir.resolve("image-" + source.getId() + "-" + System.currentTimeMillis() + "." + ext);
            List<String> command;
            if ("remove-background".equalsIgnoreCase(request.getOperation())) {
                Path rembg = Path.of(props.localPythonPath());
                command = List.of(rembg.toString(), "-m", "rembg", "i", input.toString(), output.toString());
                task.setEngine("rembg");
            } else {
                int quality = Math.max(2, Math.min(100, request.getQuality() == null ? 90 : request.getQuality()));
                command = imageMagickCommand(input, output, request, quality);
                if (command == null) {
                    String filter = imageFilter(request);
                    command = List.of(props.getFfmpeg(), "-y", "-i", input.toString(), "-vf", filter,
                            "-frames:v", "1", "-q:v", String.valueOf(Math.max(1, Math.round((100 - quality) / 10f))), output.toString());
                    task.setEngine("FFmpeg fallback");
                } else {
                    task.setEngine("ImageMagick");
                }
            }
            registerOutput(id, output);
            update(task, "running", 35, "正在调用本地图片处理引擎：" + task.getEngine());
            cancellation.throwIfCancelled();
            ProcRunner.Result result = runTask(command, timeoutFor(id, 180), cancellation);
            cancellation.throwIfCancelled();
            if (result.code() == -2) {
                failTask(task, "MEDIA_TIMEOUT", "图片处理超过任务时限");
                return;
            }
            if (!result.ok() || !Files.isRegularFile(output) || Files.size(output) < 128) throw new IllegalStateException("图片处理失败：" + tail(result.out()));
            update(task, "running", 80, "正在登记生成图片");
            cancellation.throwIfCancelled();
            Material generated = materialService.register(output.toString(), source.getFolderId(), false, Material.Source.generated, null, cancellation);
            generated.setRole(MaterialRole.product);
            generated.setTags((source.getTags() == null ? "" : source.getTags() + ",") + "图片工具," + request.getOperation());
            materials.save(generated);
            materialService.attachBrowserUrls(generated);
            setResults(task, List.of(generated));
            cancellation.throwIfCancelled();
            update(task, "done", 100, "图片处理完成，原图未覆盖");
            processRegistry.forgetOutput(cancellation, output);
        } catch (java.util.concurrent.CancellationException cancelled) {
            markCancelled(id);
        } catch (Exception e) {
            if (isCancelled(id)) markCancelled(id);
            else failTask(task, "MEDIA_EXECUTION_FAILED", e.getMessage() == null ? "图片处理失败" : e.getMessage());
        }
    }

    private void runSeparation(String id, Long materialId, ProcessRegistry.CancellationContext cancellation) {
        Task task = tasks.get(id);
        try {
            cancellation.throwIfCancelled();
            task.setEngine("Demucs");
            update(task, "running", 10, "正在准备音频分离任务");
            AudioEngineService.SeparationResult result = audioEngine.separateMaterial(materialId, cancellation);
            cancellation.throwIfCancelled();
            setResults(task, List.of(result.getVocals(), result.getInstrumental()));
            update(task, "done", 100, result.getMessage());
        } catch (java.util.concurrent.CancellationException cancelled) {
            markCancelled(id);
        } catch (Exception e) {
            if (isCancelled(id)) markCancelled(id);
            else failTask(task, "MEDIA_EXECUTION_FAILED", e.getMessage() == null ? "音频分离失败" : e.getMessage());
        }
    }

    private void runSplit(String id, Long materialId, double clipSec, ProcessRegistry.CancellationContext cancellation) {
        Task task = tasks.get(id);
        try {
            task.setEngine("FFmpeg");
            cancellation.throwIfCancelled();
            update(task, "running", 10, "正在分析视频并生成独立片段");
            List<Material> clips = materialService.splitVideo(materialId, clipSec, cancellation);
            cancellation.throwIfCancelled();
            setResults(task, clips);
            update(task, "done", 100, "视频切段完成，原视频未覆盖");
        } catch (java.util.concurrent.CancellationException cancelled) {
            markCancelled(id);
        } catch (Exception e) {
            if (isCancelled(id)) markCancelled(id);
            else failTask(task, "MEDIA_EXECUTION_FAILED", e.getMessage() == null ? "视频切段失败" : e.getMessage());
        }
    }

    private void runCover(String id, Material source, Path input, CoverRequest request, double start, double end,
                          ProcessRegistry.CancellationContext cancellation) {
        Task task = tasks.get(id);
        try {
            task.setEngine("FFmpeg drawbox");
            update(task, "running", 20, "正在生成字幕遮盖结果（有损处理，原文件保留）");
            Path dir = props.mediaToolsOutput().resolve("subtitle-covers");
            Files.createDirectories(dir);
            String ext = source.getFileType() == Material.FileType.image ? "png" : "mp4";
            Path output = dir.resolve("cover-" + source.getId() + "-" + System.currentTimeMillis() + "." + ext);
            registerOutput(id, output);
            cancellation.throwIfCancelled();
            int x = request.getX();
            int y = request.getY();
            int width = request.getWidth();
            int height = request.getHeight();
            String color = safeCoverColor(request.getColor());
            boolean ok = source.getFileType() == Material.FileType.image
                    ? ffmpeg.coverImageRect(input.toString(), x, y, width, height, color, output, cancellation)
                    : ffmpeg.coverVideoRect(input.toString(), x, y, width, height, color, start, end, output, cancellation);
            cancellation.throwIfCancelled();
            if (!ok) throw new IllegalStateException("字幕遮盖失败，请检查 FFmpeg 和遮盖区域");
            FfmpegTool.MediaInfo outputInfo = ffmpeg.probe(output.toString(), cancellation);
            if (source.getFileType() == Material.FileType.video && (!outputInfo.isHasVideo() || outputInfo.getDuration() < 0.1)) throw new IllegalStateException("遮盖结果校验失败，原文件已保留");
            if (source.getFileType() == Material.FileType.image && !Files.isRegularFile(output)) throw new IllegalStateException("图片遮盖结果校验失败，原文件已保留");
            Material generated = materialService.register(output.toString(), source.getFolderId(), false, Material.Source.generated, null, cancellation);
            generated.setRole(source.getRole() == null ? MaterialRole.none : source.getRole());
            generated.setTags(joinTags(source.getTags(), "字幕遮盖,有损处理"));
            materials.save(generated);
            materialService.attachBrowserUrls(generated);
            setResults(task, List.of(generated));
            cancellation.throwIfCancelled();
            update(task, "done", 100, "字幕遮盖完成（有损处理），原文件未覆盖；无法从烧录字幕恢复原画面");
            processRegistry.forgetOutput(cancellation, output);
        } catch (java.util.concurrent.CancellationException cancelled) {
            markCancelled(id);
        } catch (Exception e) {
            if (isCancelled(id)) markCancelled(id);
            else failTask(task, "MEDIA_EXECUTION_FAILED", e.getMessage() == null ? "字幕遮盖失败" : e.getMessage());
        }
    }

    private String safeCoverColor(String color) {
        String value = color == null ? "black@1.0" : color.trim();
        if (!value.matches("[A-Za-z]+(?:@[0-9.]+)?")) throw new IllegalArgumentException("遮盖颜色不受支持");
        return value;
    }

    private void runTimeline(String id, Material source, Path input, TimelineRequest request, FfmpegTool.MediaInfo sourceInfo,
                             ProcessRegistry.CancellationContext cancellation) {
        Task task = tasks.get(id);
        try {
            cancellation.throwIfCancelled();
            task.setEngine("FFmpeg timeline");
            update(task, "running", 10, "正在校验时间线并准备视频轨道");
            List<double[]> retained = retainedRanges(request, sourceInfo.getDuration());
            if (retained.isEmpty()) throw new IllegalArgumentException("删除区间覆盖了全部视频，至少需要保留 0.1 秒内容");
            boolean keepAudio = !"silent".equals(request.getAudioMode()) && sourceInfo.isHasAudio();
            Path dir = props.mediaToolsOutput().resolve("timeline-edits");
            Files.createDirectories(dir);
            Path output = dir.resolve("timeline-" + source.getId() + "-" + System.currentTimeMillis() + ".mp4");
            update(task, "running", 35, "正在按时间线生成新视频");
            registerOutput(id, output);
            cancellation.throwIfCancelled();
            if (!ffmpeg.editVideoRanges(input.toString(), retained, keepAudio, output, cancellation)) {
                throw new IllegalStateException("FFmpeg 未生成有效时间线视频，请检查视频编码和本机 FFmpeg");
            }
            cancellation.throwIfCancelled();
            FfmpegTool.MediaInfo outputInfo = ffmpeg.probe(output.toString(), cancellation);
            if (!outputInfo.isHasVideo() || outputInfo.getDuration() < 0.1 || Files.size(output) < 1024) {
                throw new IllegalStateException("输出视频校验失败，原视频已保留");
            }
            if (keepAudio && !outputInfo.isHasAudio()) throw new IllegalStateException("输出音轨校验失败，原视频已保留");
            if (!keepAudio && outputInfo.isHasAudio()) throw new IllegalStateException("输出仍包含音轨，原视频已保留");
            update(task, "running", 80, "正在记录处理结果");
            String sourcePolicySummary = "原视频已保留";
            if ("computer_only".equals(request.getResultPolicy())) {
                setFileResult(task, output, "video", "仅保存到电脑，原视频已保留");
            } else {
                cancellation.throwIfCancelled();
                Material generated = materialService.register(output.toString(), source.getFolderId(), false, Material.Source.generated, null, cancellation);
                cancellation.throwIfCancelled();
                generated.setRole(source.getRole() == null ? MaterialRole.body : source.getRole());
                generated.setTags(joinTags(source.getTags(), "时间线编辑," + request.getAudioMode()));
                materials.save(generated);
                materialService.attachBrowserUrls(generated);
                setResults(task, List.of(generated));
                if ("library_delete_original".equals(request.getResultPolicy())) {
                    try {
                        MaterialDeleteService.DeleteResult deleted = materialDeleteService.confirm(source.getId());
                        sourcePolicySummary = deleted.getDeletedFiles().isEmpty()
                                ? "结果已入库；源素材记录已删除，但源文件未删除（不在应用管理目录或文件不存在）"
                                : "结果已入库；源素材及文件已删除";
                    } catch (Exception deleteError) {
                        sourcePolicySummary = "结果已入库；源素材保留，删除未执行：" + safeMessage(deleteError);
                    }
                }
            }
            String audioSummary = keepAudio ? ("unmute".equals(request.getAudioMode()) ? "已保留并恢复原音轨" : "已保留原音轨") : "已输出静音视频";
            cancellation.throwIfCancelled();
            update(task, "done", 100, String.format(Locale.ROOT,
                    "时间线编辑完成：保留 %.1f 秒，%s，%s", outputInfo.getDuration(), audioSummary, sourcePolicySummary));
            processRegistry.forgetOutput(cancellation, output);
        } catch (java.util.concurrent.CancellationException cancelled) {
            markCancelled(id);
        } catch (Exception e) {
            if (isCancelled(id)) markCancelled(id);
            else failTask(task, "MEDIA_EXECUTION_FAILED", e.getMessage() == null ? "时间线编辑失败" : e.getMessage());
        }
    }

    private void runAutoTrim(String id, Long materialId, ProcessRegistry.CancellationContext cancellation) {
        Task task = tasks.get(id);
        try {
            Material source = materials.findById(materialId).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
            Path input = safeMaterialPath(source);
            task.setEngine("Auto-Editor");
            update(task, "running", 10, "正在分析静音区间");
            Path dir = props.mediaToolsOutput().resolve("auto-trim");
            Files.createDirectories(dir);
            Path output = dir.resolve("trim-" + source.getId() + "-" + System.currentTimeMillis() + "." + extensionOf(input.toString()));
            registerOutput(id, output);
            cancellation.throwIfCancelled();
            // Run as a module so copied virtual environments do not depend on a build-machine console shim.
            List<String> cmd = List.of(props.localPythonPath(), "-m", "auto_editor", input.toString(),
                    "--output", output.toString(),
                    "--edit", "audio",
                    "--when-silent", "cut",
                    "--margin", "0.2s",
                    "--no-open");
            update(task, "running", 40, "正在调用 Auto-Editor 智能剪辑");
            cancellation.throwIfCancelled();
            ProcRunner.Result result = runTask(cmd, timeoutFor(id, 1800), cancellation);
            cancellation.throwIfCancelled();
            if (result.code() == -2) {
                failTask(task, "MEDIA_TIMEOUT", "智能剪除超过任务时限");
                return;
            }
            if (!result.ok() || !Files.isRegularFile(output) || Files.size(output) < 1024) {
                // 降级策略：auto-editor 缺失/执行失败时回退 FFmpeg silenceremove 静音裁剪，
                // 避免工具缺失直接导致整单失败；仅当回退也失败时才抛可读错误。
                Path ffOutput = dir.resolve("trim-ff-" + source.getId() + "-" + System.currentTimeMillis() + "." + extensionOf(input.toString()));
                registerOutput(id, ffOutput);
                cancellation.throwIfCancelled();
                List<String> ffCmd = List.of(props.getFfmpeg(), "-y", "-i", input.toString(),
                        "-af", "silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.5,areverse,silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.5,areverse",
                        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", ffOutput.toString());
                ProcRunner.Result ffResult = runTask(ffCmd, timeoutFor(id, 1800), cancellation);
                cancellation.throwIfCancelled();
                if (ffResult.ok() && Files.isRegularFile(ffOutput) && Files.size(ffOutput) >= 1024) {
                    update(task, "running", 80, "Auto-Editor 不可用，已降级 FFmpeg 静音裁剪");
                    cancellation.throwIfCancelled();
                    Material generated = materialService.register(ffOutput.toString(), source.getFolderId(), false, Material.Source.generated, null, cancellation);
                    generated.setRole(MaterialRole.body);
                    generated.setTags((source.getTags() == null ? "" : source.getTags() + ",") + "智能剪除,ffmpeg-silenceremove");
                    materials.save(generated);
                    materialService.attachBrowserUrls(generated);
                    setResults(task, List.of(generated));
                    cancellation.throwIfCancelled();
                    update(task, "done", 100, "静音/废片剪除完成（降级：Auto-Editor 不可用，已用 FFmpeg 静音裁剪），原视频未覆盖");
                    processRegistry.forgetOutput(cancellation, ffOutput);
                    return;
                }
                throw new IllegalStateException("智能剪除失败：" + tail(result.out()));
            }
            update(task, "running", 80, "正在登记剪除结果");
            cancellation.throwIfCancelled();
            Material generated = materialService.register(output.toString(), source.getFolderId(), false, Material.Source.generated, null, cancellation);
            generated.setRole(MaterialRole.body);
            generated.setTags((source.getTags() == null ? "" : source.getTags() + ",") + "智能剪除,auto-editor");
            materials.save(generated);
            materialService.attachBrowserUrls(generated);
            setResults(task, List.of(generated));
            cancellation.throwIfCancelled();
            update(task, "done", 100, "静音/废片剪除完成，原视频未覆盖");
            processRegistry.forgetOutput(cancellation, output);
        } catch (java.util.concurrent.CancellationException cancelled) {
            markCancelled(id);
        } catch (Exception e) {
            if (isCancelled(id)) markCancelled(id);
            else failTask(task, "MEDIA_EXECUTION_FAILED", e.getMessage() == null ? "智能剪除失败" : e.getMessage());
        }
    }

    private void validateTimelineRequest(TimelineRequest request, double duration) {
        double start = request.getSourceStart() == null ? 0 : request.getSourceStart();
        double end = request.getSourceEnd() == null ? duration : request.getSourceEnd();
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end > duration + 0.01 || end - start < 0.1) {
            throw new IllegalArgumentException("入点和出点必须在视频时长内，且至少保留 0.1 秒");
        }
        List<TimeRange> sorted = new ArrayList<>(request.getRemoveRanges() == null ? List.of() : request.getRemoveRanges());
        sorted.sort(Comparator.comparing(range -> range == null || range.getStart() == null ? Double.NEGATIVE_INFINITY : range.getStart()));
        double previousEnd = start;
        for (TimeRange range : sorted) {
            if (range == null || range.getStart() == null || range.getEnd() == null
                    || !Double.isFinite(range.getStart()) || !Double.isFinite(range.getEnd())
                    || range.getStart() < start || range.getEnd() > end || range.getEnd() - range.getStart() < 0.05) {
                throw new IllegalArgumentException("每个删除区间必须位于入点和出点内，且至少为 0.05 秒");
            }
            if (range.getStart() < previousEnd - 0.001) throw new IllegalArgumentException("删除区间不能重叠");
            previousEnd = range.getEnd();
        }
    }

    private List<double[]> retainedRanges(TimelineRequest request, double duration) {
        double start = request.getSourceStart() == null ? 0 : request.getSourceStart();
        double end = request.getSourceEnd() == null ? duration : request.getSourceEnd();
        List<TimeRange> removes = new ArrayList<>(request.getRemoveRanges() == null ? List.of() : request.getRemoveRanges());
        removes.sort(Comparator.comparing(TimeRange::getStart));
        List<double[]> retained = new ArrayList<>();
        double cursor = start;
        for (TimeRange remove : removes) {
            if (remove.getStart() - cursor >= 0.05) retained.add(new double[]{cursor, remove.getStart()});
            cursor = remove.getEnd();
        }
        if (end - cursor >= 0.05) retained.add(new double[]{cursor, end});
        return retained;
    }

    private void setFileResult(Task task, Path output, String fileType, String message) {
        MaterialResult result = new MaterialResult();
        result.setName(output.getFileName().toString());
        result.setFileType(fileType);
        result.setFilePath(output.toAbsolutePath().normalize().toString());
        result.setInLibrary(false);
        task.setMaterials(List.of());
        task.setResults(List.of(result));
        task.setResultPaths(List.of(result.getFilePath()));
        task.setMessage(message);
    }

    private String joinTags(String existing, String extra) {
        if (existing == null || existing.isBlank()) return extra;
        return existing.contains(extra) ? existing : existing + "," + extra;
    }

    private void setResults(Task task, List<Material> materials) {
        List<Material> safeMaterials = materials == null ? List.of() : materials.stream().filter(java.util.Objects::nonNull).toList();
        task.setMaterials(safeMaterials);
        task.setResults(safeMaterials.stream().map(MaterialResult::from).toList());
        task.setResultPaths(safeMaterials.stream().map(Material::getFilePath).filter(java.util.Objects::nonNull).toList());
    }

    private String extensionOf(String path) {
        String name = Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "mp4";
    }

    /** ImageMagick 图片处理命令；随包路径优先、已安装 PATH 次之，均不可用时回退 FFmpeg。 */
    private List<String> imageMagickCommand(Path input, Path output, ImageRequest request, int quality) {
        String magick = props.portableTool("imagemagick/magick.exe");
        if (magick == null && runner.available("magick", "-version")) magick = "magick";
        if (magick == null) return null;
        List<String> cmd = new ArrayList<>(List.of(magick, input.toString()));
        int width = bound(request.getWidth(), 32, 4096, 0);
        int height = bound(request.getHeight(), 32, 4096, 0);
        if (width > 0 && height > 0) {
            cmd.add("-resize");
            cmd.add(width + "x" + height);
        }
        int rotate = request.getRotate() == null ? 0 : Math.floorMod(request.getRotate(), 360);
        if (rotate == 90 || rotate == 180 || rotate == 270) {
            cmd.add("-rotate");
            cmd.add(String.valueOf(rotate));
        }
        if (Boolean.TRUE.equals(request.getFlipHorizontal())) cmd.add("-flop");
        if (Boolean.TRUE.equals(request.getFlipVertical())) cmd.add("-flip");
        cmd.add("-quality");
        cmd.add(String.valueOf(quality));
        cmd.add(output.toString());
        return cmd;
    }

    private String imageFilter(ImageRequest request) {
        StringBuilder filter = new StringBuilder();
        int width = bound(request.getWidth(), 32, 4096, 0);
        int height = bound(request.getHeight(), 32, 4096, 0);
        if (width > 0 && height > 0) filter.append("scale=").append(width).append(":").append(height).append(":force_original_aspect_ratio=decrease");
        else filter.append("scale=iw:ih");
        int rotate = request.getRotate() == null ? 0 : Math.floorMod(request.getRotate(), 360);
        if (rotate == 90) filter.append(",transpose=1");
        else if (rotate == 180) filter.append(",transpose=1,transpose=1");
        else if (rotate == 270) filter.append(",transpose=2");
        if (Boolean.TRUE.equals(request.getFlipHorizontal())) filter.append(",hflip");
        if (Boolean.TRUE.equals(request.getFlipVertical())) filter.append(",vflip");
        return filter.toString();
    }

    private int bound(Integer value, int min, int max, int fallback) {
        if (value == null) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private Path safeMaterialPath(Material source) {
        Path path = Path.of(source.getFilePath() == null ? "" : source.getFilePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IllegalArgumentException("素材文件不可读取");
        return path;
    }

    private void update(Task task, String status, int progress, String message) {
        if (task == null) return;
        if (!"cancelled".equals(status) && isCancelled(task.getId())) {
            markCancelled(task.getId());
            return;
        }
        task.setStatus(status);
        task.setPhase(phaseFor(status, message));
        task.setProgress(Math.max(0, Math.min(100, progress)));
        task.setMessage(message);
        task.setUpdatedAt(System.currentTimeMillis());
        persist(task.getId());
    }

    private void failTask(Task task, String code, String message) {
        if (task == null) return;
        if (isCancelled(task.getId())) {
            markCancelled(task.getId());
            return;
        }
        task.setErrorCode(code);
        task.setError(message);
        task.setRecoveryState("none");
        update(task, "failed", task.getProgress(), message);
    }

    private int timeoutFor(String id, int fallbackSec) {
        return mediaTaskRepo.findByTaskKey(id)
                .map(task -> Math.max(1, task.getTimeoutSec() == null ? fallbackSec : task.getTimeoutSec()))
                .orElse(Math.max(1, fallbackSec));
    }

    private ProcRunner.Result runTask(List<String> command, long timeoutSec,
                                      ProcessRegistry.CancellationContext context) {
        return taskRunner == null ? runner.run(command, timeoutSec) : taskRunner.run(command, timeoutSec, context);
    }

    private String phaseFor(String status, String message) {
        if ("pending".equals(status)) return "queued";
        if ("done".equals(status)) return "finished";
        if ("failed".equals(status) || "cancelled".equals(status)) return "finished";
        String text = message == null ? "" : message;
        if (text.contains("登记") || text.contains("记录结果")) return "registering";
        if (text.contains("分析") || text.contains("探测") || text.contains("校验")) return "probing";
        return "processing";
    }

    private String tail(String value) {
        if (value == null || value.isBlank()) return "无诊断输出";
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() > 400 ? clean.substring(clean.length() - 400) : clean;
    }
}
