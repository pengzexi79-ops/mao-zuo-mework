package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.domain.MaterialTranscript;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.MediaCapabilityRouter;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.MaterialTranscriptStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, deterministic material inspection. It never uploads user media. */
@Slf4j
@Service
public class MaterialDiagnosisService {
    private final AppProps props;
    private final MaterialStore materials;
    private final MaterialTranscriptStore transcriptStore;
    private final FfmpegTool ffmpeg;
    private final ProcRunner runner;
    private final MediaCapabilityRouter capabilityRouter;
    private final ObjectMapper om = new ObjectMapper();

    public MaterialDiagnosisService(AppProps props, MaterialStore materials, MaterialTranscriptStore transcriptStore,
                                    FfmpegTool ffmpeg, ProcRunner runner) {
        this(props, materials, transcriptStore, ffmpeg, runner, new MediaCapabilityRouter(props, runner));
    }

    @Autowired
    public MaterialDiagnosisService(AppProps props, MaterialStore materials, MaterialTranscriptStore transcriptStore,
                                    FfmpegTool ffmpeg, ProcRunner runner, MediaCapabilityRouter capabilityRouter) {
        this.props = props;
        this.materials = materials;
        this.transcriptStore = transcriptStore;
        this.ffmpeg = ffmpeg;
        this.runner = runner;
        this.capabilityRouter = capabilityRouter;
    }

    @Data
    public static class Diagnosis {
        private Long materialId;
        private String level;
        private String contentType;
        private boolean usable;
        private String roleSuggestion;
        private List<String> tags = new ArrayList<>();
        private List<String> ocrTexts = new ArrayList<>();
        private List<TranscriptCue> transcript = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
        private String summary;
        /** Transcript status from persistent store: pending, running, completed, failed, or null if never requested. */
        private String transcriptStatus;
        /** Whether this material has transcribeForSubtitles enabled. */
        private Boolean transcribeForSubtitles;
        /** Whether this material has muteOriginalAudio enabled. */
        private Boolean muteOriginalAudio;
        /** 自动生产质量准入判定（复用底层视频/音频质量探测）。 */
        private QualityGateResult qualityGate;
    }

    @Data
    public static class TranscriptCue {
        private double start;
        private double end;
        private String text;
    }

    /** 自动生产质量准入判定结果。reasons 阻断准入；warnings 仅提示复核、不阻断。 */
    @Data
    public static class QualityGateResult {
        private boolean admitted = true;
        private List<String> reasons = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    /** 视频超过该时长跳过深度质量闸门（长素材基本不可能是占位/测试片，且全片解码成本过高）。 */
    private static final double GATE_MAX_VIDEO_SEC = 180;
    /** 音频超过该时长跳过深度质量闸门。 */
    private static final double GATE_MAX_AUDIO_SEC = 600;
    private static final double BLACK_RATIO = 0.50;
    private static final double RED_MAGENTA_RATIO = 0.30;
    private static final double SOLID_COLOR_RATIO = 0.70;
    private static final double FROZEN_RATIO = 0.70;
    /** 文件名疑似占位/测试素材时，叠加这些较弱的内容信号也会拒绝（避免仅凭名字误杀）。 */
    private static final double FROZEN_WEAK = 0.25;
    private static final double SOLID_WEAK = 0.25;
    private static final double BLACK_WEAK = 0.25;
    private static final double RED_WEAK = 0.15;
    private static final double AUDIO_SILENCE_RATIO = 0.70;
    /** 全片音量均值低于该值视为静音/噪声底。 */
    private static final double AUDIO_MIN_MEAN_DB = -45.0;
    private static final Pattern PLACEHOLDER_EN = Pattern.compile("\\b(demo|test|sample|placeholder|dummy|mock|color)\\b");
    private static final List<String> PLACEHOLDER_CJK = List.of(
            "占位", "示例", "样例", "演示", "色卡", "纯色", "测试片", "测试素材", "测试视频", "占位图", "占位符", "样片", "效果图");

    /**
     * 自动生产质量准入闸门：决定素材是否可以作为自动混剪候选。
     *
     * <p>纯色/黑场/红品红错误帧/冻结静帧/空音轨/静音噪声底素材一律拒绝，并给出可执行原因。
     * 静态图片（产品图、人工导入）不做硬性拒绝——人工仍可保留和管理，自动 B-roll 排除由规划层负责。
     * 任何探测异常都 fail-open（放行并提示复核），绝不因为闸门故障挡住正常素材。
     */
    public QualityGateResult qualityGate(Material material) {
        QualityGateResult gate = new QualityGateResult();
        if (material == null || material.getFilePath() == null) return gate;
        try {
            capabilityRouter.materialInput(material);
            if (material.getFileType() == Material.FileType.image) {
                return gate;
            }
            String hint = placeholderHint(material.getName());
            if (material.getFileType() == Material.FileType.video) {
                gateVideo(material, gate, hint);
            } else {
                gateAudio(material, gate, hint);
            }
        } catch (Exception e) {
            log.warn("quality gate failed for {}: {}", material.getFilePath(), e.toString());
            gate.getWarnings().add("质量准入检测失败，已按可参与处理，请人工复核");
        }
        return gate;
    }

    private void gateVideo(Material material, QualityGateResult gate, String hint) {
        Path input = materialPath(material);
        FfmpegTool.MediaInfo info = ffmpeg.probe(input.toString());
        double duration = Math.max(1.0, info.getDuration());
        if (duration > GATE_MAX_VIDEO_SEC) {
            gate.getWarnings().add("视频时长超过 " + (long) GATE_MAX_VIDEO_SEC + "s，跳过深度质量闸门，请人工确认画面非占位素材");
            return;
        }
        FfmpegTool.VideoQuality vq = ffmpeg.videoQuality(input);
        if (!vq.isReadable()) {
            reject(gate, "画面无法解码，无法参与自动混剪；请重新导出为标准 MP4 后重新探测");
            return;
        }
        double blackRatio = vq.getBlackSec() / duration;
        double redRatio = vq.getRedMagentaSec() / duration;
        double solidRatio = vq.getSolidColorSec() / duration;
        double frozenRatio = vq.getFrozenSec() / duration;
        if (blackRatio >= BLACK_RATIO) {
            reject(gate, "黑屏/黑场占比 " + pct(blackRatio) + "，疑似占位或故障素材；请替换为正式画面或删除该素材");
            return;
        }
        if (redRatio >= RED_MAGENTA_RATIO) {
            reject(gate, "纯红/品红占位错误帧占比 " + pct(redRatio) + "，疑似故障或占位素材；请替换为正式画面或删除该素材");
            return;
        }
        if (solidRatio >= SOLID_COLOR_RATIO) {
            reject(gate, "整段纯色画面占比 " + pct(solidRatio) + "，疑似色卡/占位/测试素材；请替换为正式画面或删除该素材");
            return;
        }
        if (frozenRatio >= FROZEN_RATIO) {
            reject(gate, "画面冻结/几乎静止占比 " + pct(frozenRatio) + "，疑似静态图转视频或静态字幕页，不适合自动混剪；请使用有真实运动的画面");
            return;
        }
        JsonNode cv = analyzeVideoOpenCv(input);
        if (cv != null && cv.path("readable").asBoolean(false)) {
            double blurryRatio = cv.path("blurryRatio").asDouble(0);
            double darkRatio = cv.path("darkRatio").asDouble(0);
            double brightRatio = cv.path("brightRatio").asDouble(0);
            if (blurryRatio >= FROZEN_RATIO) {
                reject(gate, "画面模糊帧占比 " + pct(blurryRatio) + "，疑似低清/虚焦/异常素材，不适合自动混剪；请替换为清晰画面");
                return;
            }
            if (blurryRatio >= 0.35) {
                gate.getWarnings().add("画面存在明显模糊帧（" + pct(blurryRatio) + "），请确认素材清晰度后用于自动混剪");
            }
            if (darkRatio >= BLACK_RATIO || brightRatio >= 0.50) {
                gate.getWarnings().add("画面亮度异常帧占比 " + pct(Math.max(darkRatio, brightRatio)) + "，请复核素材曝光");
            }
        }
        if (hint != null) {
            if (frozenRatio >= FROZEN_WEAK || solidRatio >= SOLID_WEAK || blackRatio >= BLACK_WEAK || redRatio >= RED_WEAK) {
                reject(gate, "文件名疑似" + hint + "素材（" + material.getName() + "），且画面存在明显静态/异常内容；若确为测试素材请删除，若为正式素材请重命名后重新探测");
                return;
            }
            gate.getWarnings().add("文件名疑似" + hint + "素材（" + material.getName() + "），请复核后再用于自动混剪");
        }
    }

    private void gateAudio(Material material, QualityGateResult gate, String hint) {
        Path input = materialPath(material);
        FfmpegTool.MediaInfo info = ffmpeg.probe(input.toString());
        double duration = Math.max(1.0, info.getDuration());
        if (duration > GATE_MAX_AUDIO_SEC) {
            gate.getWarnings().add("音频时长超过 " + (long) GATE_MAX_AUDIO_SEC + "s，跳过深度质量闸门，请人工确认音轨非空音/噪声");
            return;
        }
        if (!info.isHasAudio()) {
            reject(gate, "音频流不可读取，无法参与自动混剪；请重新导出后重新探测");
            return;
        }
        FfmpegTool.AudioQuality aq = ffmpeg.audioQuality(input);
        if (!aq.isReadable()) {
            reject(gate, "音频无法解码，无法参与自动混剪；请重新导出为标准音频后重新探测");
            return;
        }
        if (aq.getMaxSilenceSec() > Math.max(3, duration * AUDIO_SILENCE_RATIO)) {
            reject(gate, "音频大部分为静音（最长连续静音 " + trimNum(aq.getMaxSilenceSec()) + "s），疑似空音轨/占位音频；请替换为真实配乐或人声");
            return;
        }
        if (aq.getMeanVolumeDb() != null && aq.getMeanVolumeDb() < AUDIO_MIN_MEAN_DB) {
            reject(gate, "音频音量均值仅 " + trimNum(aq.getMeanVolumeDb()) + " dB，疑似静音/噪声底素材；请替换为有实际内容的音轨");
            return;
        }
        if (hint != null) {
            gate.getWarnings().add("文件名疑似" + hint + "素材（" + material.getName() + "），请复核后再用于自动混剪");
        }
    }

    private void reject(QualityGateResult gate, String reason) {
        gate.setAdmitted(false);
        gate.getReasons().add(reason);
    }

    /** OpenCV 画面质量分析：模糊/暗帧/过曝帧比例。能力不可用时返回 null（闸门 fail-open）。 */
    private JsonNode analyzeVideoOpenCv(Path video) {
        try {
            ProcRunner.Result run = runner.run(capabilityRouter.videoQualityCommand(video), 300);
            if (!run.ok()) return null;
            return om.readTree(run.out());
        } catch (Exception e) {
            log.debug("OpenCV video analysis unavailable: {}", e.toString());
            return null;
        }
    }

    private String placeholderHint(String name) {
        if (name == null || name.isBlank()) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        Matcher en = PLACEHOLDER_EN.matcher(lower);
        if (en.find()) return en.group(1);
        for (String token : PLACEHOLDER_CJK) {
            if (name.contains(token)) return token;
        }
        return null;
    }

    private String pct(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100);
    }

    private String trimNum(double d) {
        return FfmpegTool.trimNum(d);
    }

    public Diagnosis inspect(Long id) {
        return inspect(id, false);
    }

    /**
     * Inspect a material. When forceRetranscribe is true, existing cached transcripts are
     * ignored and a fresh transcription is run.
     */
    public Diagnosis inspect(Long id, boolean forceRetranscribe) {
        Material material = materials.findById(id).orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        Diagnosis out = new Diagnosis();
        out.setMaterialId(id);
        out.setUsable(material.getStatus() == Material.Status.ready);
        out.setRoleSuggestion(material.getRole().name());
        out.setTranscribeForSubtitles(Boolean.TRUE.equals(material.getTranscribeForSubtitles()));
        out.setMuteOriginalAudio(Boolean.TRUE.equals(material.getMuteOriginalAudio()));
        if (!out.isUsable()) out.getIssues().add("素材尚未准备完成或媒体探测失败");
        if (material.getFileType() == Material.FileType.audio) {
            inspectAudio(material, out, forceRetranscribe);
        } else {
            inspectVisual(material, out, forceRetranscribe);
        }
        QualityGateResult gate = qualityGate(material);
        out.setQualityGate(gate);
        if (!gate.isAdmitted()) {
            out.setUsable(false);
            out.getIssues().addAll(gate.getReasons());
        }
        out.getIssues().addAll(gate.getWarnings());
        classify(out, material);
        out.setSummary(buildSummary(out));
        return out;
    }

    private void inspectAudio(Material material, Diagnosis out, boolean forceRetranscribe) {
        Path input = materialPath(material);
        FfmpegTool.MediaInfo info = ffmpeg.probe(input.toString());
        if (!info.isHasAudio() || info.getDuration() < 0.5) {
            out.setUsable(false);
            out.getIssues().add("没有可播放的音频");
            return;
        }
        FfmpegTool.AudioQuality quality = ffmpeg.audioQuality(input);
        if (!quality.isReadable() || quality.getMaxSilenceSec() > Math.max(3, info.getDuration() * 0.7)) {
            out.setUsable(false);
            out.getIssues().add("音频存在异常静音");
        }
        if (Boolean.TRUE.equals(material.getTranscribeForSubtitles()) || forceRetranscribe) {
            out.getTranscript().addAll(transcribe(material, forceRetranscribe));
            out.setTranscriptStatus(lookupTranscriptStatus(material.getId()));
        } else {
            out.setTranscriptStatus(lookupTranscriptStatus(material.getId()));
        }
        out.getTags().add("音频");
    }

    private void inspectVisual(Material material, Diagnosis out, boolean forceRetranscribe) {
        Path input = materialPath(material);
        FfmpegTool.MediaInfo info = ffmpeg.probe(input.toString());
        if (material.getFileType() == Material.FileType.video && !info.isHasVideo()) {
            out.setUsable(false);
            out.getIssues().add("视频流不可读取");
            return;
        }
        if (material.getFileType() == Material.FileType.video && info.getDuration() < 1) {
            out.setUsable(false);
            out.getIssues().add("视频小于 1 秒，不适合混剪");
        }
        if (info.getWidth() < 540 || info.getHeight() < 540) out.getIssues().add("分辨率偏低，建议仅作补充画面");
        if (info.getWidth() > 0 && info.getHeight() > 0 && info.getHeight() < info.getWidth()) {
            out.getIssues().add("横屏素材，出竖屏时可能需要裁切");
        }
        if (material.getFileType() == Material.FileType.video) {
            out.getOcrTexts().addAll(ocrVideoFrames(material, 5));
            if (info.isHasAudio()) {
                if (Boolean.TRUE.equals(material.getTranscribeForSubtitles()) || forceRetranscribe) {
                    out.getTranscript().addAll(transcribe(material, forceRetranscribe));
                }
                out.setTranscriptStatus(lookupTranscriptStatus(material.getId()));
            }
        } else if (material.getFileType() == Material.FileType.image) {
            out.getOcrTexts().addAll(readOcrFrame(material, 0));
        }
    }

    /**
     * Look up the latest transcript status from the persistent store.
     */
    private String lookupTranscriptStatus(Long materialId) {
        return transcriptStore.findByMaterialId(materialId)
                .map(MaterialTranscript::getStatus)
                .orElse(null);
    }

    /**
     * Retrieve cached transcript cues for a material. Returns empty list if no completed
     * transcript exists or if cues cannot be parsed.
     */
    public List<TranscriptCue> getCachedTranscript(Long materialId) {
        MaterialTranscript record = transcriptStore.findByMaterialId(materialId).orElse(null);
        if (record == null || !"completed".equals(record.getStatus()) || record.getCues() == null) {
            return List.of();
        }
        try {
            List<TranscriptCue> cues = new ArrayList<>();
            for (JsonNode item : om.readTree(record.getCues())) {
                String text = item.path("text").asText("").trim();
                if (text.isBlank()) continue;
                TranscriptCue cue = new TranscriptCue();
                cue.setStart(item.path("start").asDouble(0));
                cue.setEnd(item.path("end").asDouble(0));
                cue.setText(text);
                if (cue.getEnd() > cue.getStart()) cues.add(cue);
            }
            return cues;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse cached transcript cues for material {}: {}", materialId, e.toString());
            return List.of();
        }
    }

    /**
     * Run ASR over an arbitrary local media file and return real timestamp cues. This is the
     * shared low-level transcription used for both original-material subtitles and AI narration
     * captions. It performs no caching or persistence — callers decide where (if anywhere) the
     * cues are stored. Returns an empty list on any failure, never throws.
     */
    public List<TranscriptCue> transcribeAudioFile(Path mediaPath) {
        if (mediaPath == null || !Files.isRegularFile(mediaPath)) return List.of();
        try {
            Path audio = Files.createTempFile(props.cache(), "asr-", ".wav");
            try {
                Path input = capabilityRouter.internalInput(mediaPath);
                ProcRunner.Result extract = runner.run(List.of(capabilityRouter.ffmpeg(), "-y", "-i", input.toString(),
                        "-vn", "-ac", "1", "-ar", "16000", audio.toString()), 180);
                if (!extract.ok()) return List.of();
                ProcRunner.Result run = runner.run(capabilityRouter.asrCommand(audio), 900);
                if (!run.ok()) return List.of();
                JsonNode root = om.readTree(run.out());
                return parseTranscriptCues(root.path("segments"));
            } finally {
                Files.deleteIfExists(audio);
            }
        } catch (Exception e) {
            log.warn("ASR failed for audio file {}: {}", mediaPath, e.toString());
            return List.of();
        }
    }

    /** Parse a {@code segments} JSON array into timestamp cues, skipping blank/zero-length entries. */
    private List<TranscriptCue> parseTranscriptCues(JsonNode segments) {
        List<TranscriptCue> cues = new ArrayList<>();
        for (JsonNode item : segments) {
            String text = item.path("text").asText("").trim();
            if (text.isBlank()) continue;
            TranscriptCue cue = new TranscriptCue();
            cue.setStart(item.path("start").asDouble(0));
            cue.setEnd(item.path("end").asDouble(0));
            cue.setText(text);
            if (cue.getEnd() > cue.getStart()) cues.add(cue);
        }
        return cues;
    }

    /**
     * Run transcription for a material, respecting the persistent transcript cache.
     * When forceRetranscribe is false and a completed transcript exists, returns the cached cues.
     * Otherwise runs fresh transcription and persists the result.
     */
    private List<TranscriptCue> transcribe(Material material, boolean forceRetranscribe) {
        // Check cache first (unless forced)
        if (!forceRetranscribe) {
            List<TranscriptCue> cached = getCachedTranscript(material.getId());
            if (!cached.isEmpty()) {
                return cached;
            }
        }

        // Delete any stale/failed records before retrying
        if (forceRetranscribe) {
            transcriptStore.deleteByMaterialId(material.getId());
        }

        // Create a pending record
        MaterialTranscript record = transcriptStore.findByMaterialId(material.getId()).orElse(null);
        if (record == null) {
            record = new MaterialTranscript();
            record.setMaterialId(material.getId());
            record.setLanguage("zh");
            record.setStatus("running");
            record = transcriptStore.save(record);
        } else if (!"running".equals(record.getStatus())) {
            record.setStatus("running");
            record.setError(null);
            record = transcriptStore.save(record);
        }

        try {
            Path audio = Files.createTempFile(props.cache(), "asr-", ".wav");
            try {
                Path input = capabilityRouter.materialInput(material);
                ProcRunner.Result extract = runner.run(List.of(capabilityRouter.ffmpeg(), "-y", "-i", input.toString(),
                        "-vn", "-ac", "1", "-ar", "16000", audio.toString()), 180);
                if (!extract.ok()) {
                    markTranscriptFailed(record, "Audio extraction failed");
                    return List.of();
                }

                ProcRunner.Result run;
                try {
                    run = runner.run(capabilityRouter.asrCommand(audio), 900);
                } catch (IllegalArgumentException | IllegalStateException routeError) {
                    markTranscriptFailed(record, routeError.getMessage());
                    return List.of();
                }
                if (!run.ok()) {
                    markTranscriptFailed(record, "ASR process returned non-zero exit code");
                    return List.of();
                }

                JsonNode root = om.readTree(run.out());
                JsonNode segments = root.path("segments");
                List<TranscriptCue> cues = new ArrayList<>();
                for (JsonNode item : segments) {
                    String text = item.path("text").asText("").trim();
                    if (text.isBlank()) continue;
                    TranscriptCue cue = new TranscriptCue();
                    cue.setStart(item.path("start").asDouble(0));
                    cue.setEnd(item.path("end").asDouble(0));
                    cue.setText(text);
                    if (cue.getEnd() > cue.getStart()) cues.add(cue);
                }

                // Persist successful transcript
                record.setCues(om.writeValueAsString(segments));
                record.setStatus("completed");
                record.setError(null);
                record.setUpdatedAt(LocalDateTime.now());
                transcriptStore.save(record);

                return cues;
            } finally {
                Files.deleteIfExists(audio);
            }
        } catch (Exception e) {
            log.warn("Transcription failed for material {}: {}", material.getId(), e.toString());
            markTranscriptFailed(record, e.getClass().getSimpleName() + ": " + e.getMessage());
            return List.of();
        }
    }

    private void markTranscriptFailed(MaterialTranscript record, String error) {
        record.setStatus("failed");
        record.setError(error);
        record.setUpdatedAt(LocalDateTime.now());
        transcriptStore.save(record);
    }

    /**
     * Retry transcription for a material, forcing a fresh run regardless of cache state.
     */
    public Diagnosis retryTranscription(Long materialId) {
        return inspect(materialId, true);
    }

    /**
     * Read OCR texts for a material without re-running transcription, for reuse by structured analysis.
     */
    public List<String> readOcrTexts(Material material) {
        if (material == null) return List.of();
        if (material.getFileType() == Material.FileType.image) return readOcrFrame(material, 0);
        if (material.getFileType() == Material.FileType.video) {
            return ocrVideoFrames(material, 5);
        }
        return List.of();
    }

    /**
     * OCR several evenly spaced frames of a video in a single model process so the
     * bundled RapidOCR model is loaded once. Covers burned-in titles, labels and
     * captions far better than a single-frame sample.
     */
    private List<String> ocrVideoFrames(Material material, int frames) {
        try {
            Path input = materialPath(material);
            FfmpegTool.MediaInfo info = ffmpeg.probe(input.toString());
            if (info == null || !info.isHasVideo() || info.getDuration() <= 0) return List.of();
            double duration = info.getDuration();
            List<Path> temps = new ArrayList<>();
            try {
                for (int i = 0; i < frames; i++) {
                    double at = duration * (i + 0.5) / frames;
                    Path temp = Files.createTempFile(props.cache(), "ocr-", ".jpg");
                    if (!ffmpeg.thumbnail(input.toString(), temp, Math.min(1, Math.max(0, at)))) {
                        Files.deleteIfExists(temp);
                        continue;
                    }
                    temps.add(temp);
                }
                if (temps.isEmpty()) return List.of();
                ProcRunner.Result run = runner.run(capabilityRouter.ocrCommand(temps), 300);
                if (!run.ok()) return List.of();
                JsonNode node = om.readTree(run.out());
                Set<String> unique = new LinkedHashSet<>();
                for (JsonNode text : node.path("ocrTexts")) {
                    if (!text.asText().isBlank()) unique.add(text.asText());
                }
                return new ArrayList<>(unique);
            } finally {
                for (Path temp : temps) Files.deleteIfExists(temp);
            }
        } catch (Exception ignore) {
            return List.of();
        }
    }
    private List<String> readOcrFrame(Material material, double at) {
        try {
            Path temp = Files.createTempFile(props.cache(), "ocr-", ".jpg");
            try {
                Path input = materialPath(material);
                if (material.getFileType() == Material.FileType.video && !ffmpeg.thumbnail(input.toString(), temp, at)) return List.of();
                if (material.getFileType() == Material.FileType.image) temp = input;
                ProcRunner.Result run = runner.run(capabilityRouter.ocrCommand(List.of(temp)), 120);
                if (!run.ok()) return List.of();
                JsonNode node = om.readTree(run.out());
                Set<String> unique = new LinkedHashSet<>();
                for (JsonNode text : node.path("ocrTexts")) if (!text.asText().isBlank()) unique.add(text.asText());
                return new ArrayList<>(unique);
            } finally {
                if (material.getFileType() == Material.FileType.video) Files.deleteIfExists(temp);
            }
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private Path materialPath(Material material) {
        return capabilityRouter.materialInput(material);
    }

    private void classify(Diagnosis out, Material material) {
        String all = String.join(" ", out.getOcrTexts()).toLowerCase(Locale.ROOT);
        if (all.matches(".*(元|￥|价格|下单|优惠|买[一二三]|套装|活动).*")) {
            out.setContentType("带货商品展示");
            out.getTags().add("带货");
            out.setRoleSuggestion(MaterialRole.product.name());
        } else if (material.getRole() == MaterialRole.hook) {
            out.setContentType("引流钩子");
        } else if (material.getRole() == MaterialRole.product) {
            out.setContentType("产品展示");
        } else {
            out.setContentType("内容素材");
        }
        if (all.matches(".*(微信|加我|私信|扫码|同行|竞品).*")) out.getIssues().add("画面文字可能含联系方式、竞品或敏感引流信息");
        if (all.matches(".*(根治|永久|百分百|100%|第一|顶级).*")) out.getIssues().add("画面文字可能含夸大宣传词，建议复核");
        if (out.getIssues().isEmpty()) out.setLevel("可用");
        else if (out.isUsable()) out.setLevel("建议复核");
        else out.setLevel("不可用");
    }

    private String buildSummary(Diagnosis out) {
        if (!out.isUsable()) return "素材不可用：" + String.join("；", out.getIssues());
        return out.getIssues().isEmpty() ? "基础质检通过，可进入混剪候选池" : "素材可用，但建议复核：" + String.join("；", out.getIssues());
    }
}
