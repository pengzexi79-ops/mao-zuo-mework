package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.DeliveryQc;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.MaterialStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Renders an already validated edit plan into one locally stored MP4. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenderService {

    private final AppProps props;
    private final FfmpegTool ffmpeg;
    private final MaterialDiagnosisService diagnosisService;
    private final MaterialStore materialStore;

    /** 可选依赖：字段注入且可为空，保证既有单测无需 Spring 容器或数据库。 */
    @Autowired(required = false)
    private DeliveryQcService deliveryQc;

    @Data
    public static class RenderResult {
        private boolean ok;
        private String filePath;
        private String publicUrl;
        private String thumbnail;
        private double durationSec;
        private String error;
        private String qcStatus;
        private String qcReport;
        /** 结构化六维质检报告（JSON），供成片库逐项展示。 */
        private String qcJson;
        private List<String> warnings = new ArrayList<>();
    }

    public RenderResult render(MixPlanner.Plan plan, MixParams params, String outName, Consumer<String> onStep) {
        return render(plan, params, outName, onStep, java.time.Instant.now().plusSeconds(1800));
    }

    public RenderResult render(MixPlanner.Plan plan, MixParams params, String outName, Consumer<String> onStep,
                               java.time.Instant deadline) {
        RenderResult result = new RenderResult();
        MixParams p = params.normalized();
        if (!plan.isUsable()) {
            result.setError("剪辑计划未达到交付下限：" + String.join("；", plan.getNotes()));
            return result;
        }
        if (plan.isRequiresExternalAudio() && isBlank(plan.getVoicePath()) && isBlank(plan.getBgmPath())) {
            result.setError("没有可覆盖全片的音轨：钩子音频只覆盖开头，不能替代 BGM或口播。请指定任意可读音频作为 BGM、导入口播，或明确选择保留原片声音/AI 人声；已在切片前停止渲染");
            return result;
        }
        if (!ffmpeg.ffmpegAvailable()) {
            result.setError("找不到 ffmpeg。请安装并把 ffmpeg/ffprobe 加入 PATH。");
            return result;
        }

        double expectedDuration = sumPlanDuration(plan);
        if (expectedDuration < 1 || expectedDuration > p.getMaxSec() + durationTolerance(expectedDuration)) {
            result.setError("剪辑计划时长异常：计划 " + FfmpegTool.trimNum(expectedDuration)
                    + "s，允许上限 " + p.getMaxSec() + "s；已拒绝渲染");
            return result;
        }
        String audioCoverageError = audioCoverageError(plan, p, expectedDuration);
        if (audioCoverageError != null) {
            result.setError(audioCoverageError);
            return result;
        }

        Path work = props.slices().resolve(UUID.randomUUID().toString().substring(0, 8));
        Path finalPath = props.output().resolve(outName.endsWith(".mp4") ? outName : outName + ".mp4");
        try {
            Files.createDirectories(work);
            step(onStep, "切片 0/" + plan.getSegments().size() + " · 0.0/"
                    + FfmpegTool.trimNum(expectedDuration) + "s");
            List<Path> clips = new ArrayList<>();
            double validatedDuration = 0;
            double acceptedPlanDuration = 0;
            int index = 0;
            boolean preserveOriginalAudio = "original".equalsIgnoreCase(p.getAudioMode());
            for (MixPlanner.Segment segment : plan.getSegments()) {
                if (!segment.isEnabled()) continue;
                if (java.time.Instant.now().isAfter(deadline)) {
                    result.setError("单条渲染超过时限，请检查素材是否损坏、被占用或降低切片数量后重试");
                    return result;
                }
                index++;
                Path clip = work.resolve(String.format("c%04d.mp4", index));

                // Stage per-material mute: if the user explicitly marked this material to mute,
                // strip its audio track at the cut stage, before global original audio retention.
                boolean muteThisClip = false;
                if (segment.getMaterialId() != null) {
                    Material mat = materialStore.findById(segment.getMaterialId()).orElse(null);
                    if (mat != null && Boolean.TRUE.equals(mat.getMuteOriginalAudio())) {
                        muteThisClip = true;
                    }
                }
                // Per-clip mute overrides global preserveOriginalAudio for this segment only.
                boolean keepAudio = preserveOriginalAudio && !muteThisClip;

                boolean cutOk = "image".equals(segment.getKind())
                        ? ffmpeg.imageToClip(segment.getFilePath(), segment.getDuration(),
                        p.getWidth(), p.getHeight(), p.getFps(), keepAudio, clip)
                        : ffmpeg.cutNormalize(segment.getFilePath(), segment.getSourceStart(), segment.getDuration(),
                        p.getWidth(), p.getHeight(), p.getFps(), keepAudio, clip);
                if (!cutOk) {
                    result.getWarnings().add("片段跳过：" + segment.getMaterialName() + "，FFmpeg 切片失败");
                    continue;
                }
                FfmpegTool.MediaInfo info = ffmpeg.probe(clip.toString());
                if (!isUsableClip(info, segment.getDuration(), p)) {
                    result.getWarnings().add("片段跳过：" + segment.getMaterialName() + "，实际时长 "
                            + FfmpegTool.trimNum(info.getDuration()) + "s 或画面规格异常");
                    Files.deleteIfExists(clip);
                    continue;
                }
                clips.add(clip);
                acceptedPlanDuration += segment.getDuration();
                validatedDuration += info.getDuration();
                step(onStep, "切片 " + index + "/" + plan.getSegments().size() + " · "
                        + FfmpegTool.trimNum(validatedDuration) + "/" + FfmpegTool.trimNum(expectedDuration) + "s");
            }
            if (clips.isEmpty()) {
                result.setError("所有片段切片均失败，请检查素材是否损坏、被占用或不可读取");
                return result;
            }
            if (!durationMatches(validatedDuration, acceptedPlanDuration)) {
                result.setError(durationError("切片", validatedDuration, acceptedPlanDuration));
                return result;
            }
            if (acceptedPlanDuration + 0.5 < expectedDuration) {
                result.getWarnings().add("部分素材切片失败或已截断：实际可用 "
                        + FfmpegTool.trimNum(validatedDuration) + "s，短于计划 "
                        + FfmpegTool.trimNum(expectedDuration) + "s；已按实际可用素材继续出片");
            }
            expectedDuration = validatedDuration;

            boolean silentOutput = "silent".equalsIgnoreCase(p.getAudioMode());
            boolean burnRequested = !silentOutput && Boolean.TRUE.equals(p.getBurnHookText())
                    && plan.getHookText() != null && !plan.getHookText().isBlank();
            double hookStart = Math.max(0, plan.getHookStartSec());
            double hookEnd = Math.min(expectedDuration, Math.max(hookStart + 0.2, plan.getHookEndSec()));
            String hookFilter = burnRequested
                    ? ffmpeg.hookTextFilter(plan.getHookText(), resolveFont(p), p.getHookFontSize(),
                    p.getHookFontColor(), hookStart, hookEnd)
                    : null;
            int captionFontSize = Math.max(26, p.getHookFontSize() - 20);
            String font = resolveFont(p);
            boolean rehookRequested = Boolean.TRUE.equals(p.getBurnRehookText())
                    && plan.getRehookText() != null && !plan.getRehookText().isBlank()
                    && plan.getRehookWindowEnd() > plan.getRehookWindowStart();
            String rehookFilter = rehookRequested
                    ? ffmpeg.hookTextFilter(plan.getRehookText(), font, p.getHookFontSize(),
                    p.getHookFontColor(), Math.max(0, plan.getRehookWindowStart()),
                    Math.min(expectedDuration, plan.getRehookWindowEnd()))
                    : null;
            List<FfmpegTool.Caption> allCaptions = new ArrayList<>();
            if (!silentOutput && Boolean.TRUE.equals(p.getAutoSubtitles())) {
                allCaptions.addAll(mapCaptions(plan));
            }
            if (!silentOutput && Boolean.TRUE.equals(p.getBurnAiVoiceCaptions())) {
                allCaptions.addAll(mapNarrationCaptions(plan, expectedDuration));
            }
            String captionsFilter = allCaptions.isEmpty()
                    ? null
                    : ffmpeg.captionsFilter(allCaptions, font, captionFontSize, p.getHookFontColor(), 16, 0.72);
            String sourceSubtitleCleanFilter = !silentOutput && Boolean.TRUE.equals(p.getCleanSourceSubtitles())
                    && "subtitle-safe-band".equalsIgnoreCase(p.getSourceSubtitleCleanMode())
                    ? ffmpeg.sourceSubtitleSafeBandFilter()
                    : null;
            String subtitleFilter = joinFilters(sourceSubtitleCleanFilter, hookFilter, rehookFilter, captionsFilter);
            if (java.time.Instant.now().isAfter(deadline)) {
                result.setError("单条渲染超过时限，已在拼接前中止");
                return result;
            }
            step(onStep, burnRequested ? "拼接并烧录钩子字幕中 · 已验证 " + FfmpegTool.trimNum(validatedDuration) + "s"
                    : "拼接中 · 已验证 " + FfmpegTool.trimNum(validatedDuration) + "s");
            Path listFile = work.resolve("list.txt");
            StringBuilder list = new StringBuilder();
            for (Path clip : clips) list.append("file '").append(escapeConcatPath(clip)).append("'\n");
            Files.writeString(listFile, list.toString(), StandardCharsets.UTF_8);
            Path silent = work.resolve("silent.mp4");
            boolean hasSubtitles = subtitleFilter != null && !subtitleFilter.isBlank();
            boolean burnedInConcat = hasSubtitles && ffmpeg.concat(listFile, silent, p.getFps(), subtitleFilter, preserveOriginalAudio);
            if (!burnedInConcat && !ffmpeg.concat(listFile, silent, p.getFps(), null, preserveOriginalAudio)) {
                result.setError("视频拼接失败，请查看后端 FFmpeg 日志");
                return result;
            }
            if (burnRequested && !burnedInConcat) {
                result.getWarnings().add("拼接阶段字幕烧录失败，已在混音后尝试兼容烧录");
            }
            double silentDuration = requiredDuration(silent, "拼接输出", result);
            if (silentDuration <= 0) return result;
            if (silentDuration > expectedDuration + durationTolerance(expectedDuration)) {
                result.setError(durationError("拼接输出", silentDuration, expectedDuration));
                return result;
            }
            if (silentDuration + durationTolerance(expectedDuration) < expectedDuration) {
                // Some source containers still lose accepted tail segments in concat despite each
                // normalized clip probing correctly. Deliver the verified playable portion; only
                // longer/looping output remains a hard failure.
                result.getWarnings().add("拼接后实际可用 " + FfmpegTool.trimNum(silentDuration)
                        + "s，短于切片校验 " + FfmpegTool.trimNum(expectedDuration)
                        + "s；已按拼接实测时长继续生成");
                expectedDuration = silentDuration;
            }

            if (java.time.Instant.now().isAfter(deadline)) {
                result.setError("单条渲染超过时限，已在混音前中止");
                return result;
            }
            Path current = silent;
            double audioDuration = silentDuration;
            if (silentOutput) {
                step(onStep, "静音封装中 · " + FfmpegTool.trimNum(silentDuration) + "s");
                FfmpegTool.MediaInfo silentInfo = ffmpeg.probe(current.toString());
                if (silentInfo.isHasAudio()) {
                    result.setError("静音模式封装异常：输出仍包含音频流");
                    return result;
                }
            } else {
                step(onStep, "混音中 · " + FfmpegTool.trimNum(silentDuration) + "s");
                Path withAudio = work.resolve("audio.mp4");
                String voicePath = plan.getVoicePath();
                if (!preserveOriginalAudio && plan.getVoiceSegments() != null && plan.getVoiceSegments().size() > 1) {
                    Path scheduledVoice = work.resolve("scheduled-voice.m4a");
                    List<FfmpegTool.AudioSlice> slices = plan.getVoiceSegments().stream()
                            .map(segment -> new FfmpegTool.AudioSlice(segment.getFilePath(), segment.getSourceStart(), segment.getDuration()))
                            .toList();
                    if (!ffmpeg.concatAudioSlices(slices, scheduledVoice)) {
                        result.setError("音频阶段失败：多段口播无法按时间线拼接，已拒绝回退为单段循环");
                        return result;
                    }
                    voicePath = scheduledVoice.toString();
                }
                boolean muxed = preserveOriginalAudio
                        ? ffmpeg.muxOriginalAudio(silent, plan.getBgmPath(), p.getOriginalAudioVolume(), p.getBgmVolume(), silentDuration, withAudio)
                        : ffmpeg.muxAudio(silent, voicePath, plan.getBgmPath(),
                        p.getBgmVolume(), Boolean.TRUE.equals(plan.getDuckBgm()), plan.getHookAudioPath(),
                        hookStart, hookEnd, p.getHookAudioVolume(), silentDuration, withAudio);
                if (!muxed) {
                    result.setError("音频阶段失败：无法生成可播放的混音轨，请更换可读 BGM/口播或选择保留原片声音");
                    result.getWarnings().add("混音失败，静音中间文件仅保留用于诊断，不进入最终成片");
                    return result;
                }
                current = withAudio;
                audioDuration = requiredDuration(current, "混音输出", result);
                if (audioDuration <= 0 || !durationMatches(audioDuration, expectedDuration)) {
                    if (audioDuration > 0) result.setError(durationError("混音输出", audioDuration, expectedDuration));
                    return result;
                }
            }

            boolean burned = burnedInConcat;
            if (burnRequested && !burnedInConcat) {
                if (java.time.Instant.now().isAfter(deadline)) {
                    result.setError("单条渲染超过时限，已在字幕处理前中止");
                    return result;
                }
                step(onStep, "兼容烧录字幕中");
                Path burnedPath = work.resolve("burn.mp4");
                double fallbackHookStart = Math.min(audioDuration, hookStart);
                double fallbackHookEnd = Math.min(audioDuration, Math.max(fallbackHookStart + 0.2, hookEnd));
                if (ffmpeg.burnText(current, plan.getHookText(), resolveFont(p), p.getHookFontSize(),
                        p.getHookFontColor(), fallbackHookStart, fallbackHookEnd, burnedPath)) {
                    current = burnedPath;
                    burned = true;
                } else {
                    result.getWarnings().add("字幕烧录失败，已输出无字幕版本");
                }
            }
            double finalDurationBeforeMove = requiredDuration(current, burned ? "字幕输出" : "最终输出", result);
            if (finalDurationBeforeMove <= 0 || !durationMatches(finalDurationBeforeMove, expectedDuration)) {
                if (finalDurationBeforeMove > 0) result.setError(durationError("最终输出", finalDurationBeforeMove, expectedDuration));
                return result;
            }
            Files.move(current, finalPath, StandardCopyOption.REPLACE_EXISTING);
            double finalDuration = requiredDuration(finalPath, "成片", result);
            if (finalDuration <= 0 || !durationMatches(finalDuration, expectedDuration)) {
                Files.deleteIfExists(finalPath);
                if (finalDuration > 0) result.setError(durationError("成片", finalDuration, expectedDuration));
                return result;
            }
            boolean subtitlesRequested = !silentOutput && (Boolean.TRUE.equals(p.getAutoSubtitles())
                    || Boolean.TRUE.equals(p.getBurnAiVoiceCaptions()));
            boolean subtitlesBurned = captionsFilter != null && !captionsFilter.isBlank();
            if (!passesDeliveryQuality(plan, p, finalPath, finalDuration, result, burned,
                    subtitlesRequested, subtitlesBurned, allCaptions.size())) {
                retainQcCandidate(finalPath, result);
                return result;
            }

            Path thumbnail = props.thumbs().resolve(finalPath.getFileName().toString().replace(".mp4", ".jpg"));
            if (ffmpeg.thumbnail(finalPath.toString(), thumbnail, Math.min(1.0, Math.max(0, finalDuration / 3)))) {
                result.setThumbnail("/files/thumbs/" + thumbnail.getFileName());
            }
            result.setOk(true);
            result.setFilePath(finalPath.toString());
            result.setPublicUrl("/files/output/" + finalPath.getFileName());
            result.setDurationSec(finalDuration);
            step(onStep, "完成 · " + FfmpegTool.trimNum(finalDuration) + "s 已通过时长校验");
            return result;
        } catch (Exception e) {
            log.error("render failed", e);
            result.setError("渲染异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        } finally {
            cleanup(work);
        }
    }

    /** Reject audio that would inevitably become a long silent tail before any video work begins. */
    private String audioCoverageError(MixPlanner.Plan plan, MixParams params, double videoDuration) {
        if (!plan.isRequiresExternalAudio() || "original".equalsIgnoreCase(params.getAudioMode())) return null;
        if (!isBlank(plan.getBgmPath())) return null; // BGM is looped by the mixer.
        if (isBlank(plan.getVoicePath())) {
            return "没有可覆盖全片的音轨：请指定任意可读音频作为 BGM，或选择保留原片声音；已在切片前停止渲染";
        }
        double voiceDuration = plan.getVoiceDurationSec();
        FfmpegTool.MediaInfo info = ffmpeg.probe(plan.getVoicePath());
        if (info != null && info.isHasAudio() && info.getAudioDuration() > 0) voiceDuration = info.getAudioDuration();
        if (voiceDuration + 0.5 < videoDuration) {
            double silentTail = Math.max(0, videoDuration - voiceDuration);
            return "口播仅 " + FfmpegTool.trimNum(voiceDuration) + "s，计划 "
                    + FfmpegTool.trimNum(videoDuration) + "s；未选择 BGM 将产生约 "
                    + FfmpegTool.trimNum(silentTail) + "s 静音。请指定任意音频作为 BGM、补足口播，或选择保留原片声音；已在切片前停止渲染";
        }
        if ("ai-voice".equalsIgnoreCase(params.getAudioMode())
                && voiceDuration > videoDuration + Math.max(1.5, videoDuration * 0.08)) {
            return "AI 口播 " + FfmpegTool.trimNum(voiceDuration) + "s，计划 "
                    + FfmpegTool.trimNum(videoDuration) + "s；已拒绝截断未读完的尾句，请缩短文案后重新生成";
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<FfmpegTool.Caption> mapCaptions(MixPlanner.Plan plan) {
        List<FfmpegTool.Caption> captions = new ArrayList<>();
        double timeline = 0;
        for (MixPlanner.Segment segment : plan.getSegments()) {
            try {
                // Only burn captions from materials that the user explicitly authorized for transcription
                // and that have a completed transcript in the persistent store.
                if (segment.getMaterialId() != null) {
                    Material mat = materialStore.findById(segment.getMaterialId()).orElse(null);
                    if (mat == null || !Boolean.TRUE.equals(mat.getTranscribeForSubtitles())) continue;
                }
                List<MaterialDiagnosisService.TranscriptCue> cues = diagnosisService.getCachedTranscript(segment.getMaterialId());
                if (cues.isEmpty()) continue;

                for (MaterialDiagnosisService.TranscriptCue cue : cues) {
                    double sourceEnd = segment.getSourceStart() + segment.getDuration();
                    if (cue.getEnd() <= segment.getSourceStart() || cue.getStart() >= sourceEnd) continue;
                    FfmpegTool.Caption caption = new FfmpegTool.Caption();
                    caption.setText(cue.getText());
                    caption.setFrom(round(timeline + Math.max(0, cue.getStart() - segment.getSourceStart())));
                    caption.setTo(round(timeline + Math.min(segment.getDuration(), cue.getEnd() - segment.getSourceStart())));
                    if (caption.getTo() > caption.getFrom() + 0.15) captions.add(caption);
                }
            } catch (Exception e) {
                log.debug("caption diagnosis skipped for material {}: {}", segment.getMaterialId(), e.toString());
            }
            timeline += segment.getDuration();
            if (captions.size() >= 80) break;
        }
        return captions;
    }

    /**
     * Map real ASR narration cues (already in video-timeline seconds) to burnable captions.
     * Cues are clamped to the validated video duration; blank/zero-length cues are dropped.
     */
    List<FfmpegTool.Caption> mapNarrationCaptions(MixPlanner.Plan plan, double expectedDuration) {
        List<FfmpegTool.Caption> captions = new ArrayList<>();
        if (plan == null || plan.getNarrationCaptions() == null) return captions;
        for (MixPlanner.Plan.CaptionCue cue : plan.getNarrationCaptions()) {
            if (cue == null || cue.getText() == null || cue.getText().isBlank()) continue;
            if (cue.getEnd() <= cue.getStart() || cue.getStart() >= expectedDuration) continue;
            FfmpegTool.Caption caption = new FfmpegTool.Caption();
            caption.setText(cue.getText());
            caption.setFrom(round(Math.max(0, cue.getStart())));
            caption.setTo(round(Math.min(expectedDuration, cue.getEnd())));
            if (caption.getTo() > caption.getFrom() + 0.15) captions.add(caption);
        }
        return captions;
    }

    private String joinFilters(String... filters) {
        List<String> usable = new ArrayList<>();
        for (String filter : filters) if (filter != null && !filter.isBlank()) usable.add(filter);
        return usable.isEmpty() ? null : String.join(",", usable);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private boolean isUsableClip(FfmpegTool.MediaInfo info, double expected, MixParams params) {
        return info.isHasVideo() && info.getWidth() == params.getWidth() && info.getHeight() == params.getHeight()
                && info.getDuration() >= Math.max(0.5, expected - 0.35)
                && info.getDuration() <= expected + durationTolerance(expected);
    }

    private double requiredDuration(Path file, String stage, RenderResult result) {
        FfmpegTool.MediaInfo info = ffmpeg.probe(file.toString());
        if (!info.isHasVideo() || info.getDuration() <= 0) {
            result.setError(stage + "不可读取或时长为 0，已拒绝输出");
            return 0;
        }
        return info.getDuration();
    }

    /** 将失败候选移出最终输出目录，仅供版本诊断与修复比较，不生成 publicUrl。 */
    private void retainQcCandidate(Path finalPath, RenderResult result) {
        try {
            Path candidates = props.cache().resolve("qc-candidates");
            Files.createDirectories(candidates);
            Path candidate = candidates.resolve(finalPath.getFileName().toString().replace(".mp4", "")
                    + "-qc-" + UUID.randomUUID().toString().substring(0, 8) + ".mp4");
            Files.move(finalPath, candidate, StandardCopyOption.REPLACE_EXISTING);
            result.setFilePath(candidate.toString());
            result.setDurationSec(ffmpeg.probe(candidate.toString()).getDuration());
            result.getWarnings().add("成片候选未通过质检，已隔离保存用于修复比较，不可下载或交付");
        } catch (Exception e) {
            try { Files.deleteIfExists(finalPath); } catch (Exception ignored) { }
            result.getWarnings().add("质检失败候选清理完成：" + e.getClass().getSimpleName());
        }
    }

    /** Final delivery gate: build the explainable six-dimension QC report and block hard audio/video failures. */
    private boolean passesDeliveryQuality(MixPlanner.Plan plan, MixParams params, Path file, double videoDuration,
                                          RenderResult result, boolean hookBurned,
                                          boolean subtitlesRequested, boolean subtitlesBurned, int subtitleCount) {
        FfmpegTool.MediaInfo info = ffmpeg.probe(file.toString());
        FfmpegTool.AudioQuality audioQuality = ffmpeg.audioQuality(file);
        FfmpegTool.VideoQuality videoQuality = ffmpeg.videoQuality(file);

        DeliveryQc report = deliveryQc != null
                ? deliveryQc.assess(plan, params, videoDuration, info, audioQuality, videoQuality,
                        hookBurned, subtitlesRequested, subtitlesBurned, subtitleCount)
                : legacyQc(info, audioQuality, videoQuality, videoDuration);

        result.setQcStatus(report.getStatus());
        result.setQcReport(report.getSummary());
        result.setQcJson(writeJson(report));
        if ("fail".equals(report.getStatus())) {
            result.setError("成品质检未通过：" + report.getSummary() + "；" + firstFailingIssue(report) + "，已拒绝输出");
            return false;
        }
        result.getWarnings().add("成品质检" + ("pass".equals(report.getStatus()) ? "通过" : "提示") + "：" + report.getSummary());
        return true;
    }

    /** Minimal audio/video-only report for callers without the Spring-injected QC service. */
    private DeliveryQc legacyQc(FfmpegTool.MediaInfo info, FfmpegTool.AudioQuality audioQuality,
                                FfmpegTool.VideoQuality videoQuality, double videoDuration) {
        DeliveryQc report = new DeliveryQc();
        DeliveryQc.CategoryResult audio = report.category("audio");
        boolean hasAudio = info.isHasAudio() && info.getAudioDuration() > 0;
        if (!hasAudio) {
            if (props.isQcAllowSilentAudio()) { audio.setStatus("warn"); audio.issue("成片没有有效声音，已按本机质检宽松设置保留"); }
            else { audio.setStatus("fail"); audio.issue("成片没有可播放的音频"); }
        } else {
            double audioDuration = info.getAudioDuration();
            if (Math.abs(audioDuration - videoDuration) > durationTolerance(videoDuration)) { audio.setStatus("fail"); audio.issue("音频时长与画面时长不一致"); }
            if (Math.abs(info.getAudioStartSec() - info.getVideoStartSec()) > props.getQcMaxAvDriftSec()) { audio.setStatus("fail"); audio.issue("音画起始偏移过大"); }
            if (!audioQuality.isReadable()) { audio.setStatus("fail"); audio.issue("无法解码最终音频"); }
            else if (audioQuality.getMaxSilenceSec() > props.getQcMaxSilenceSec()) { audio.setStatus("fail"); audio.issue("连续静音超过上限"); }
            audio.check("音频可解码，时长 " + FfmpegTool.trimNum(audioDuration) + "s");
        }
        DeliveryQc.CategoryResult video = report.category("video");
        if (!videoQuality.isReadable()) { video.setStatus("fail"); video.issue("无法解码最终画面"); }
        else {
            if (videoQuality.getBlackSec() / Math.max(0.1, videoDuration) > props.getQcMaxBlackRatio()) { video.setStatus("fail"); video.issue("黑屏比例过高"); }
            if (videoQuality.getRedMagentaSec() / Math.max(0.1, videoDuration) > props.getQcMaxRedMagentaRatio()) { video.setStatus("fail"); video.issue("异常纯红/品红错误帧比例过高"); }
            video.check("画面可解码，时长 " + FfmpegTool.trimNum(videoDuration) + "s");
        }
        report.resolve();
        return report;
    }

    private static String firstFailingIssue(DeliveryQc report) {
        for (DeliveryQc.CategoryResult category : report.getCategories()) {
            if ("fail".equals(category.getStatus()) && !category.getIssues().isEmpty()) {
                return category.getCategory() + "：" + category.getIssues().get(0);
            }
        }
        return "未通过硬性质检";
    }

    private String writeJson(DeliveryQc report) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report);
        } catch (Exception e) {
            return null;
        }
    }

    private double sumPlanDuration(MixPlanner.Plan plan) {
        return plan.getSegments().stream().filter(MixPlanner.Segment::isEnabled)
                .mapToDouble(MixPlanner.Segment::getDuration).sum();
    }

    private boolean durationMatches(double actual, double expected) {
        return Math.abs(actual - expected) <= durationTolerance(expected)
                && actual <= expected + durationTolerance(expected);
    }

    private double durationTolerance(double expected) {
        return Math.max(3.0, expected * 0.10);
    }

    private String durationError(String stage, double actual, double expected) {
        return stage + "时长校验失败：实际 " + FfmpegTool.trimNum(actual) + "s，计划 "
                + FfmpegTool.trimNum(expected) + "s，容差 ±" + FfmpegTool.trimNum(durationTolerance(expected))
                + "s。已拒绝保存该成片，避免循环或超长无效视频。";
    }

    private String escapeConcatPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "/").replace("'", "\\'");
    }

    private String resolveFont(MixParams params) {
        if (params.getFontFile() != null && !params.getFontFile().isBlank()) return params.getFontFile();
        String windowsRoot = System.getenv("WINDIR");
        String[] candidates = {
                windowsRoot == null || windowsRoot.isBlank() ? null : Path.of(windowsRoot, "Fonts", "msyhbd.ttc").toString(),
                windowsRoot == null || windowsRoot.isBlank() ? null : Path.of(windowsRoot, "Fonts", "msyh.ttc").toString(),
                windowsRoot == null || windowsRoot.isBlank() ? null : Path.of(windowsRoot, "Fonts", "simhei.ttf").toString(),
                "/System/Library/Fonts/PingFang.ttc", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
        };
        for (String candidate : candidates) if (candidate != null && Files.exists(Path.of(candidate))) return candidate;
        return null;
    }

    private void cleanup(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignore) {
                }
            });
        } catch (Exception ignore) {
        }
    }

    private void step(Consumer<String> callback, String message) {
        if (callback == null) return;
        try {
            callback.accept(message);
        } catch (Exception ignore) {
        }
    }
}
