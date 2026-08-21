package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.MaterialAnalysis;
import com.douyin.mixcut.domain.MaterialSegment;
import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.MaterialTranscript;
import com.douyin.mixcut.domain.UseCase;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.MaterialAnalysisStore;
import com.douyin.mixcut.repository.MaterialSegmentStore;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.MaterialTranscriptStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 素材结构化分析：异步跑 ffmpeg 场景检测（失败则均匀切片兜底），
 * 复用已有 OCR / 转写数据，可选地调用 AI {@link UseCase#tag} 生成结构化标签。
 * 分析结果与镜头片段分别持久化到 material_analysis / material_segment。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialAnalysisService {

    private static final String INDEX_VERSION = "material-index-v3";
    private static final double SCENE_THRESHOLD = 0.4;
    private static final double MIN_SEGMENT_SEC = 0.5;
    private static final double FALLBACK_SLICE_SEC = 3.0;
    private static final double SAMPLE_INTERVAL_SEC = 5.0;
    private static final int MAX_SEGMENTS = 300;
    private static final int MAX_SAMPLE_FRAMES = 60;

    private final MaterialStore materialStore;
    private final MaterialAnalysisStore analysisStore;
    private final MaterialSegmentStore segmentStore;
    private final MaterialTranscriptStore transcriptStore;
    private final MaterialDiagnosisService diagnosisService;
    private final AiService aiService;
    private final FfmpegTool ffmpeg;
    private final AppProps props;
    private final ObjectMapper om = new ObjectMapper();

    @Qualifier("analysisExecutor")
    private final Executor analysisExecutor;

    /** 进程内去重：同一素材的分析不会并发重复派发。 */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    /** 内部分析片段（尚未落库）。 */
    private record SegmentSpec(double start, double end, Double score) {
    }

    private record SegmentResult(String source, List<SegmentSpec> specs) {
    }

    private record FrameResult(List<MaterialSegment> segments, List<Map<String, Object>> samples,
                               List<String> issues) {
    }

    /**
     * 启动异步分析。先把记录置为 running 并立即返回，真正的检测在工作线程执行。
     * 已在运行则幂等返回当前记录，不重复派发。
     */
    public MaterialAnalysis analyze(Long materialId) {
        return analyze(materialId, false);
    }

    /** Reuses an unchanged completed index; force creates a new audited attempt. */
    public MaterialAnalysis analyze(Long materialId, boolean force) {
        Material material = materialStore.findById(materialId).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        String fingerprint = fingerprint(material);
        MaterialAnalysis analysis = analysisStore.findByMaterialId(materialId).orElseGet(MaterialAnalysis::new);
        if (running.contains(materialId) || "running".equals(analysis.getStatus())) return analysis;
        if (!force && isReusable(analysis, fingerprint)) return analysis;
        analysis.setMaterialId(materialId);
        analysis.setSourceFingerprint(fingerprint);
        analysis.setIndexVersion(INDEX_VERSION);
        analysis.setAttemptCount((analysis.getAttemptCount() == null ? 0 : analysis.getAttemptCount()) + 1);
        analysis.setStatus("running");
        analysis.setError(null);
        analysisStore.save(analysis);

        final Long analysisId = analysis.getId();
        if (!running.add(materialId)) {
            return analysis;
        }
        try {
            analysisExecutor.execute(() -> {
                try {
                    runAnalysis(materialId, analysisId);
                } finally {
                    running.remove(materialId);
                }
            });
        } catch (RuntimeException saturated) {
            running.remove(materialId);
            analysis.setStatus("failed");
            analysis.setError("分析队列已满，请稍后重试");
            analysisStore.save(analysis);
            throw new IllegalStateException("分析队列已满，请稍后重试");
        }
        return analysis;
    }

    /**
     * Queues only unanalysed, renderable videos from the request's existing application scope.
     * It never walks arbitrary local directories. The bounded queue keeps preparation responsive
     * on large libraries; later preparation runs continue with the next eligible materials.
     */
    public int queueUnanalysedAuthorizedVisuals(com.douyin.mixcut.dto.MixParams params, int limit) {
        com.douyin.mixcut.dto.MixParams p = params == null ? new com.douyin.mixcut.dto.MixParams().normalized() : params.normalized();
        Set<Long> materialIds = p.getMaterialIds() == null ? Set.of() : new LinkedHashSet<>(p.getMaterialIds());
        Set<Long> folderIds = new LinkedHashSet<>();
        if (p.getFolderIds() != null) folderIds.addAll(p.getFolderIds());
        if (Boolean.TRUE.equals(p.getStrictFolderSequence()) && p.getFolderReadSteps() != null) {
            for (var step : p.getFolderReadSteps()) {
                if (step == null || Boolean.FALSE.equals(step.getEnabled())) continue;
                if (step.getFolderId() != null) folderIds.add(step.getFolderId());
                if ("fallback".equalsIgnoreCase(step.getShortagePolicy()) && step.getFallbackFolderId() != null) {
                    folderIds.add(step.getFallbackFolderId());
                }
            }
        }
        int bound = Math.max(1, Math.min(24, limit));
        int queued = 0;
        for (Material material : materialStore.findAll().stream()
                .filter(item -> item.getFileType() == Material.FileType.video)
                .filter(item -> item.getStatus() == Material.Status.ready)
                .filter(item -> item.getDurationSec() != null && item.getDurationSec() >= 1.0)
                .filter(item -> materialIds.isEmpty() || materialIds.contains(item.getId()))
                .filter(item -> folderIds.isEmpty() || folderIds.contains(item.getFolderId()))
                .sorted(java.util.Comparator.comparing(Material::getId, java.util.Comparator.nullsLast(Long::compareTo)).reversed())
                .toList()) {
            if (queued >= bound) break;
            MaterialAnalysis existing = analysisStore.findByMaterialId(material.getId()).orElse(null);
            if (existing != null && ("running".equals(existing.getStatus()) || "completed".equals(existing.getStatus()))) continue;
            try {
                analyze(material.getId());
                queued++;
            } catch (IllegalStateException saturated) {
                break;
            }
        }
        return queued;
    }

    /** 回收重启或异常中断后遗留的状态，不删除任何素材、分析或镜头数据。 */
    @Scheduled(fixedDelayString = "${app.analysis-watchdog-delay-ms:30000}")
    public void recoverStaleAnalyses() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        for (MaterialAnalysis analysis : analysisStore.findByStatusBefore("running", cutoff)) {
            Long materialId = analysis.getMaterialId();
            if (materialId == null || running.contains(materialId)) continue;
            analysis.setStatus("failed");
            analysis.setError("分析任务长时间无活动，可能因服务重启中断；请重新分析");
            analysisStore.save(analysis);
            log.warn("recovered stale material analysis {} for material {}", analysis.getId(), materialId);
        }
    }

    /** 读取最新分析结果 + 镜头片段 + 转写缓存，供前端展示。 */
    public Map<String, Object> read(Long materialId) {
        MaterialAnalysis analysis = analysisStore.findByMaterialId(materialId).orElse(null);
        List<MaterialSegment> segments = segmentStore.findByMaterialId(materialId);
        List<MaterialDiagnosisService.TranscriptCue> transcript = diagnosisService.getCachedTranscript(materialId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis", analysis);
        result.put("segments", segments);
        result.put("transcript", transcript);
        result.put("tags", parseStringArray(analysis == null ? null : analysis.getTagsJson()));
        result.put("ocrTexts", parseStringArray(analysis == null ? null : analysis.getOcrTextsJson()));
        result.put("issues", parseStringArray(analysis == null ? null : analysis.getIssuesJson()));
        result.put("sampleFrames", parseObjectArray(analysis == null ? null : analysis.getSampleFramesJson()));
        return result;
    }

    void runAnalysis(Long materialId, Long analysisId) {
        try {
            Material material = materialStore.findById(materialId).orElse(null);
            if (material == null) {
                markFailed(analysisId, materialId, "素材不存在");
                return;
            }

            SegmentResult detection = detectSegments(material);
            FrameResult frames = prepareFrames(material, analysisId, detection.specs());
            List<MaterialSegment> segments = frames.segments();

            List<String> ocrTexts = diagnosisService.readOcrTexts(material);
            String transcriptStatus = transcriptStore.findByMaterialId(materialId)
                    .map(MaterialTranscript::getStatus)
                    .orElse(null);
            List<MaterialDiagnosisService.TranscriptCue> transcript = diagnosisService.getCachedTranscript(materialId);
            List<String> tags = resolveTags(material, ocrTexts, transcript);
            List<String> issues = new ArrayList<>(buildIssues(material, detection, segments));
            issues.addAll(frames.issues());

            MaterialAnalysis analysis = analysisStore.findByMaterialId(materialId).orElseGet(MaterialAnalysis::new);
            analysis.setMaterialId(materialId);
            analysis.setStatus("completed");
            analysis.setSource(detection.source());
            analysis.setTagsJson(writeArray(tags));
            analysis.setOcrTextsJson(writeArray(ocrTexts));
            analysis.setTranscriptStatus(transcriptStatus);
            analysis.setIssuesJson(writeArray(issues));
            analysis.setSampleFramesJson(writeObjects(frames.samples()));
            analysis.setSummary(buildSummary(material, detection, segments, ocrTexts, tags));
            analysis.setError(null);
            analysis.setIndexedAt(LocalDateTime.now());
            analysisStore.save(analysis);
            refineRoleByAnalysis(material, tags, ocrTexts, analysis.getSummary());
        } catch (Exception e) {
            log.warn("material analysis failed for {}: {}", materialId, e.toString());
            markFailed(analysisId, materialId, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** AI 分类精化：导入时角色按文件名兜底（body/bgm），分析完成后用 AI 标签/OCR/摘要二次推断更精确角色。
     *  只在角色为自动兜底（body/bgm/none）时升级，绝不覆盖用户手动指定的角色。 */
    private void refineRoleByAnalysis(Material material, List<String> tags, List<String> ocrTexts, String summary) {
        try {
            MaterialRole current = material.getRole();
            if (current != MaterialRole.body && current != MaterialRole.bgm && current != MaterialRole.none) return;
            StringBuilder sb = new StringBuilder();
            for (String s : tags) if (s != null) sb.append(s).append(' ');
            for (String s : ocrTexts) if (s != null) sb.append(s).append(' ');
            if (summary != null) sb.append(summary);
            String text = sb.toString().toLowerCase(java.util.Locale.ROOT);
            if (text.isBlank()) return;
            MaterialRole refined = null;
            if (containsAny(text, "商品", "产品", "带货", "卖点", "详情页", "本品", "主推")) refined = MaterialRole.product;
            else if (containsAny(text, "口播", "测评", "种草", "开箱", "试色", "使用感", "体验")) refined = MaterialRole.body;
            else if (containsAny(text, "明星", "达人", "代言", "采访", "出镜")) refined = MaterialRole.celebrity;
            else if (containsAny(text, "结尾", "转化", "下单", "关注", "加购", "引导")) refined = MaterialRole.endcard;
            else if (containsAny(text, "开头", "钩子", "悬念", "吸睛", "抓住")) refined = MaterialRole.hook;
            if (refined != null && refined != current) {
                material.setRole(refined);
                materialStore.save(material);
            }
        } catch (Exception e) {
            log.debug("refine role by analysis skipped for {}: {}", material.getId(), e.toString());
        }
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) if (text.contains(k)) return true;
        return false;
    }
    // ---------------- 检测与片段 ----------------

    private SegmentResult detectSegments(Material material) {
        if (material.getFileType() == Material.FileType.image) {
            double d = Math.max(3.0, duration(material));
            return new SegmentResult("fallback", List.of(new SegmentSpec(0, d, null)));
        }
        if (material.getFileType() == Material.FileType.audio) {
            double d = duration(material);
            return d > 0
                    ? new SegmentResult("fallback", List.of(new SegmentSpec(0, d, null)))
                    : new SegmentResult("fallback", List.of());
        }
        // video: 场景检测优先，失败/无输出走均匀切片兜底
        double duration = duration(material);
        List<SegmentSpec> specs = buildFromCuts(duration, ffmpeg.detectSceneCuts(material.getFilePath(), SCENE_THRESHOLD));
        if (!specs.isEmpty()) return new SegmentResult("scene", specs);
        return new SegmentResult("fallback", uniformSegments(duration));
    }

    private List<SegmentSpec> buildFromCuts(double duration, List<FfmpegTool.SceneCut> cuts) {
        if (cuts == null || cuts.isEmpty()) return List.of();
        List<SegmentSpec> specs = new ArrayList<>();
        double prev = 0;
        for (FfmpegTool.SceneCut cut : cuts) {
            double t = cut.getTime();
            if (t > prev + MIN_SEGMENT_SEC && t <= duration && specs.size() < MAX_SEGMENTS) {
                specs.add(new SegmentSpec(prev, t, cut.getScore()));
                prev = t;
            }
        }
        if (duration - prev >= MIN_SEGMENT_SEC && specs.size() < MAX_SEGMENTS) {
            specs.add(new SegmentSpec(prev, duration, null));
        }
        return specs;
    }

    private List<SegmentSpec> uniformSegments(double duration) {
        List<SegmentSpec> specs = new ArrayList<>();
        for (double start = 0; start < duration - 0.15 && specs.size() < MAX_SEGMENTS; start += FALLBACK_SLICE_SEC) {
            double end = Math.min(start + FALLBACK_SLICE_SEC, duration);
            if (end - start < MIN_SEGMENT_SEC) break;
            specs.add(new SegmentSpec(start, end, null));
        }
        return specs;
    }

    private FrameResult prepareFrames(Material material, Long analysisId, List<SegmentSpec> specs) {
        segmentStore.deleteByMaterialId(material.getId());
        List<MaterialSegment> segments = new ArrayList<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        boolean video = material.getFileType() == Material.FileType.video;
        String key = video ? frameKey(material) : null;
        Path frameDir = video ? props.thumbs() : null;
        if (video) cleanupOldAnalysisFrames(material.getId(), frameDir, key);

        int idx = 0;
        for (SegmentSpec spec : specs) {
            MaterialSegment segment = new MaterialSegment();
            segment.setMaterialId(material.getId());
            segment.setAnalysisId(analysisId);
            segment.setIdx(idx);
            segment.setStartSec(round(spec.start()));
            segment.setEndSec(round(spec.end()));
            segment.setDurationSec(round(spec.end() - spec.start()));
            segment.setScore(spec.score() == null ? null : round(spec.score()));
            if (video) {
                double sourceDuration = Math.max(0, duration(material));
                double safeEnd = Math.max(spec.start(), Math.min(spec.end(), Math.max(0, sourceDuration - 0.05)));
                double at = round(Math.max(0, Math.min(safeEnd, (spec.start() + safeEnd) / 2.0)));
                Path frame = frameDir.resolve("a" + material.getId() + "-" + key + "-s" + idx + ".jpg");
                String url = "/files/thumbs/" + frame.getFileName();
                if (!Files.isRegularFile(frame) && !ffmpeg.analysisFrame(material.getFilePath(), frame, at)) {
                    issues.add("片段代表帧抽取失败：第 " + (idx + 1) + " 段");
                    url = null;
                }
                segment.setRepresentativeFrameAtSec(at);
                segment.setRepresentativeFrameUrl(url);
            }
            segments.add(segment);
            idx++;
        }

        if (video) {
            double duration = duration(material);
            int sampleIdx = 0;
            for (double at = 0; at < duration && sampleIdx < MAX_SAMPLE_FRAMES; at += SAMPLE_INTERVAL_SEC) {
                double safeAt = round(Math.min(at, Math.max(0, duration - 0.05)));
                Path frame = frameDir.resolve("a" + material.getId() + "-" + key + "-p" + sampleIdx + ".jpg");
                String url = "/files/thumbs/" + frame.getFileName();
                if (!Files.isRegularFile(frame) && !ffmpeg.analysisFrame(material.getFilePath(), frame, safeAt)) {
                    issues.add("固定间隔采样帧抽取失败：" + safeAt + " 秒");
                } else {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("atSec", safeAt);
                    sample.put("url", url);
                    samples.add(sample);
                }
                sampleIdx++;
            }
        }
        if (!segments.isEmpty()) segmentStore.insertBatch(segments);
        return new FrameResult(segments, samples, issues);
    }

    private String frameKey(Material material) {
        String fingerprint = fingerprint(material);
        String raw = material.getId() + "-" + INDEX_VERSION + "-" + fingerprint;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private void cleanupOldAnalysisFrames(Long materialId, Path frameDir, String currentKey) {
        String prefix = "a" + materialId + "-";
        try (DirectoryStream<Path> files = Files.newDirectoryStream(frameDir, prefix + "*.jpg")) {
            for (Path file : files) {
                if (!file.getFileName().toString().contains("-" + currentKey + "-")) Files.deleteIfExists(file);
            }
        } catch (Exception e) {
            log.debug("清理素材分析旧代表帧失败 {}: {}", materialId, e.toString());
        }
    }

    // ---------------- OCR / 转写 / 标签 ----------------

    private List<String> resolveTags(Material material, List<String> ocrTexts,
                                     List<MaterialDiagnosisService.TranscriptCue> transcript) {
        try {
            JsonNode node = aiService.askJson(UseCase.tag,
                    "你是短视频素材打标助手。仅根据提供的素材名、OCR 文字与转写文本输出结构化标签，不要编造。",
                    buildTagContext(material, ocrTexts, transcript) + "\n\n严格只输出 {\"tags\":[\"标签1\",\"标签2\"]}",
                    0.3, 120, null);
            if (node != null && node.path("tags").isArray()) {
                List<String> tags = new ArrayList<>();
                for (JsonNode item : node.path("tags")) {
                    String text = item.asText(null);
                    if (text != null && !text.isBlank() && !tags.contains(text)) tags.add(text.trim());
                }
                if (!tags.isEmpty()) return tags;
            }
        } catch (Exception e) {
            log.debug("AI 标签生成失败，回退到既有素材标签: {}", e.toString());
        }
        return fallbackTags(material);
    }

    private String buildTagContext(Material material, List<String> ocrTexts,
                                   List<MaterialDiagnosisService.TranscriptCue> transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("素材名: ").append(material.getName() == null ? "" : material.getName());
        if (!ocrTexts.isEmpty()) sb.append("\nOCR 文字: ").append(String.join(" / ", ocrTexts));
        if (!transcript.isEmpty()) {
            String text = transcript.stream()
                    .map(MaterialDiagnosisService.TranscriptCue::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .reduce((a, b) -> a + " " + b).orElse("");
            if (!text.isBlank()) sb.append("\n转写文本: ").append(text);
        }
        return sb.toString();
    }

    /** 无 AI 或 AI 失败时的确定性回退：沿用素材既有标签。 */
    private List<String> fallbackTags(Material material) {
        if (material.getTags() == null || material.getTags().isBlank()) return List.of();
        Set<String> tags = new LinkedHashSet<>();
        for (String part : material.getTags().split(",")) {
            String text = part.trim();
            if (!text.isBlank()) tags.add(text);
        }
        return new ArrayList<>(tags);
    }

    // ---------------- 汇总 ----------------

    private List<String> buildIssues(Material material, SegmentResult detection, List<MaterialSegment> segments) {
        List<String> issues = new ArrayList<>();
        if ("fallback".equals(detection.source()) && material.getFileType() == Material.FileType.video) {
            issues.add("镜头检测不可用，已使用均匀切片兜底");
        }
        if (segments.isEmpty()) issues.add("未识别到可用片段");
        return issues;
    }

    private String buildSummary(Material material, SegmentResult detection, List<MaterialSegment> segments,
                                List<String> ocrTexts, List<String> tags) {
        String sourceLabel = "scene".equals(detection.source()) ? "镜头检测" : "均匀切片";
        return String.format(Locale.ROOT,
                "识别 %d 个片段（来源：%s），OCR %d 段文字，标签 %d 个%s",
                segments.size(), sourceLabel, ocrTexts.size(), tags.size(),
                segments.isEmpty() ? "；请确认素材可正常读取" : "");
    }

    // ---------------- 工具 ----------------

    private double duration(Material material) {
        if (material.getDurationSec() != null && material.getDurationSec() > 0) return material.getDurationSec();
        try {
            return ffmpeg.probe(material.getFilePath()).getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    private void markFailed(Long analysisId, Long materialId, String error) {
        MaterialAnalysis analysis = analysisStore.findByMaterialId(materialId).orElseGet(MaterialAnalysis::new);
        analysis.setMaterialId(materialId);
        analysis.setStatus("failed");
        analysis.setError(error);
        analysisStore.save(analysis);
    }

    /** Bounded batch entry point for the current library scope; never walks arbitrary directories. */
    public Map<String, Object> queueIndex(List<Long> materialIds, boolean force, int limit) {
        int bound = Math.max(1, Math.min(48, limit));
        Set<Long> requested = materialIds == null ? Set.of() : new LinkedHashSet<>(materialIds);
        int queued = 0, reused = 0, skipped = 0;
        for (Material material : materialStore.findAll()) {
            if (queued >= bound) break;
            if (!requested.isEmpty() && !requested.contains(material.getId())) continue;
            if (material.getStatus() != Material.Status.ready) { skipped++; continue; }
            MaterialAnalysis before = analysisStore.findByMaterialId(material.getId()).orElse(null);
            String fingerprint = fingerprint(material);
            if (!force && isReusable(before, fingerprint)) { reused++; continue; }
            try { analyze(material.getId(), force); queued++; } catch (IllegalStateException full) { break; }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queued", queued); result.put("reused", reused); result.put("skipped", skipped);
        result.put("limit", bound); result.put("indexVersion", INDEX_VERSION);
        return result;
    }

    private boolean isReusable(MaterialAnalysis analysis, String fingerprint) {
        return analysis != null && "completed".equals(analysis.getStatus())
                && INDEX_VERSION.equals(analysis.getIndexVersion())
                && fingerprint.equals(analysis.getSourceFingerprint());
    }

    private String fingerprint(Material material) {
        try {
            Path path = Path.of(material.getFilePath());
            if (!Files.isRegularFile(path)) return "missing:" + material.getFilePath();
            String value = path.toRealPath() + "|" + Files.size(path) + "|" + Files.getLastModifiedTime(path).toMillis();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "unreadable:" + material.getId();
        }
    }

    private String writeArray(List<String> values) {
        try {
            return om.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String writeObjects(List<Map<String, Object>> values) {
        try {
            return om.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Map<String, Object>> parseObjectArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = om.readTree(json);
            if (!node.isArray()) return List.of();
            List<Map<String, Object>> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isObject()) values.add(om.convertValue(item, Map.class));
            }
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = om.readTree(json);
            if (!node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String text = item.asText(null);
                if (text != null && !text.isBlank()) values.add(text);
            }
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
