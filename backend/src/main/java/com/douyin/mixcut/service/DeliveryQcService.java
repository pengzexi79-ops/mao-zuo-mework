package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.DeliveryQc;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成片交付质检引擎：把渲染结果与剪辑计划合成一份可解释的九维质检报告。
 *
 * <p>audio/video 是可拦截的硬门槛；subtitle/duplicate/semantic/hook/exposure/rhythm/subtitleSync 是提示性维度。
 * 当 {@link MixParams#getStrictDelivery()} 显式开启（AI 自动出片/严格交付流水线）时，hook/duplicate/subtitleSync
 * 三条提示性维度升级为硬拦截（warn → fail），保证自动产线不会把缺钩子文案、片段重复或字幕错位的成片直接交付。
 * 默认关闭，兼容既有人工/半自动交付行为。
 * 该服务不执行 ffmpeg，只消费 RenderService 已经探测到的媒体信息，因此可以脱离
 * 真实媒体环境做纯单元测试。</p>
 */
@Service
@RequiredArgsConstructor
public class DeliveryQcService {

    private final AppProps props;

    public DeliveryQc assess(MixPlanner.Plan plan, MixParams params, double videoDuration,
                             FfmpegTool.MediaInfo info, FfmpegTool.AudioQuality audioQuality,
                             FfmpegTool.VideoQuality videoQuality, boolean hookBurned,
                             boolean subtitlesRequested, boolean subtitlesBurned, int subtitleCount) {
        DeliveryQc report = new DeliveryQc();
        double tolerance = durationTolerance(videoDuration);
        // AI 自动出片 / 严格交付：显式开启后，hook/duplicate/subtitleSync 提示升级为硬拦截；默认关闭保持兼容。
        boolean strict = params != null && Boolean.TRUE.equals(params.getStrictDelivery());

        if (params != null && "silent".equalsIgnoreCase(params.getAudioMode())) {
            assessSilentOutput(report, info);
        } else {
            assessAudio(report, info, audioQuality, videoDuration, tolerance);
        }
        assessVideo(report, info, videoQuality, params, videoDuration);
        assessSubtitle(report, subtitlesRequested, subtitlesBurned, subtitleCount);
        assessDuplicate(report, plan, strict);
        assessSemantic(report, plan, strict);
        assessHook(report, plan, hookBurned, strict);
        assessBrandExposure(report, plan);
        assessRhythm(report, plan);
        assessSubtitleSync(report, plan, videoDuration, strict);

        report.resolve();
        return report;
    }

    // ---------------- audio / video 硬门槛 ----------------

    private void assessSilentOutput(DeliveryQc report, FfmpegTool.MediaInfo info) {
        DeliveryQc.CategoryResult audio = report.category("audio");
        if (info != null && info.isHasAudio()) {
            audio.setStatus("fail");
            audio.issue("静音模式输出仍包含音频流");
            return;
        }
        audio.check("静音模式已验证：输出不含音频流");
    }

    private void assessAudio(DeliveryQc report, FfmpegTool.MediaInfo info, FfmpegTool.AudioQuality audioQuality,
                             double videoDuration, double tolerance) {
        DeliveryQc.CategoryResult audio = report.category("audio");
        boolean hasAudio = info != null && info.isHasAudio() && info.getAudioDuration() > 0;

        if (!hasAudio) {
            if (props.isQcAllowSilentAudio()) {
                audio.setStatus("warn");
                audio.issue("成片没有有效声音，已按本机质检宽松设置保留");
            } else {
                audio.setStatus("fail");
                audio.issue("成片没有可播放的音频，已拒绝输出");
            }
            return;
        }

        double audioDuration = info.getAudioDuration() > 0 ? info.getAudioDuration() : videoDuration;
        if (Math.abs(audioDuration - videoDuration) > tolerance) {
            audio.setStatus("fail");
            audio.issue("音频时长 " + FfmpegTool.trimNum(audioDuration) + "s 与画面时长 "
                    + FfmpegTool.trimNum(videoDuration) + "s 不一致");
        }
        double avDrift = info.getAudioStartSec() - info.getVideoStartSec();
        if (Math.abs(avDrift) > props.getQcMaxAvDriftSec()) {
            audio.setStatus("fail");
            audio.issue("音画起始偏移 " + FfmpegTool.trimNum(Math.abs(avDrift)) + "s 超过上限");
        }
        if (audioQuality == null || !audioQuality.isReadable()) {
            audio.setStatus("fail");
            audio.issue("无法解码最终音频");
        } else if (audioQuality.getMaxSilenceSec() > props.getQcMaxSilenceSec()) {
            audio.setStatus("fail");
            audio.issue("检测到连续 " + FfmpegTool.trimNum(audioQuality.getMaxSilenceSec())
                    + "s 静音，超过 " + FfmpegTool.trimNum(props.getQcMaxSilenceSec()) + "s 上限");
        }

        audio.check("音频可解码，时长 " + FfmpegTool.trimNum(audioDuration) + "s");
        audio.check("最长静音 "
                + (audioQuality == null ? "-" : FfmpegTool.trimNum(audioQuality.getMaxSilenceSec()) + "s"));
        audio.check("音画起始偏移 " + FfmpegTool.trimNum(Math.abs(avDrift)) + "s");
    }

    private void assessVideo(DeliveryQc report, FfmpegTool.MediaInfo info, FfmpegTool.VideoQuality videoQuality,
                             MixParams params, double videoDuration) {
        DeliveryQc.CategoryResult video = report.category("video");
        if (videoQuality == null || !videoQuality.isReadable()) {
            video.setStatus("fail");
            video.issue("无法解码最终画面");
            return;
        }
        double blackRatio = videoQuality.getBlackSec() / Math.max(0.1, videoDuration);
        if (blackRatio > props.getQcMaxBlackRatio()) {
            video.setStatus("fail");
            video.issue("黑屏累计 " + FfmpegTool.trimNum(videoQuality.getBlackSec()) + "s，超过允许比例");
        }
        double redMagentaRatio = videoQuality.getRedMagentaSec() / Math.max(0.1, videoDuration);
        if (redMagentaRatio > props.getQcMaxRedMagentaRatio()) {
            video.setStatus("fail");
            video.issue("检测到异常纯红/品红错误帧累计 " + FfmpegTool.trimNum(videoQuality.getRedMagentaSec())
                    + "s，超过允许比例");
        }
        if (info != null && info.isHasVideo()) {
            int expectedW = params == null || params.getWidth() == null ? 0 : params.getWidth();
            int expectedH = params == null || params.getHeight() == null ? 0 : params.getHeight();
            if ((expectedW > 0 && info.getWidth() != expectedW) || (expectedH > 0 && info.getHeight() != expectedH)) {
                video.setStatus(warn(video.getStatus()));
                video.issue("画面规格 " + info.getWidth() + "x" + info.getHeight()
                        + " 与目标 " + expectedW + "x" + expectedH + " 不一致");
            }
        }
        video.check("画面可解码，时长 " + FfmpegTool.trimNum(videoDuration) + "s");
        video.check("黑屏累计 " + (videoQuality == null ? "-" : FfmpegTool.trimNum(videoQuality.getBlackSec()) + "s"));
        video.check("纯红/品红累计 " + (videoQuality == null ? "-" : FfmpegTool.trimNum(videoQuality.getRedMagentaSec()) + "s"));
        video.check("画面规格 " + (info == null ? "-" : info.getWidth() + "x" + info.getHeight()));
    }

    // ---------------- subtitle / duplicate / semantic / hook 提示性维度 ----------------

    private void assessSubtitle(DeliveryQc report, boolean requested, boolean burned, int count) {
        DeliveryQc.CategoryResult subtitle = report.category("subtitle");
        if (!requested) {
            subtitle.check("未请求字幕");
            return;
        }
        if (count <= 0) {
            subtitle.setStatus("warn");
            subtitle.issue("已请求字幕但未生成任何可烧录字幕，请确认素材已授权转录或已启用 AI 配音字幕");
            return;
        }
        if (!burned) {
            subtitle.setStatus("warn");
            subtitle.issue("已生成 " + count + " 条字幕但未成功烧录");
        } else {
            subtitle.check("已烧录 " + count + " 条字幕");
        }
    }

    private void assessDuplicate(DeliveryQc report, MixPlanner.Plan plan, boolean strict) {
        DeliveryQc.CategoryResult duplicate = report.category("duplicate");
        List<MixPlanner.Segment> segments = plan == null ? List.of() : plan.getSegments();
        int exact = 0, adjacent = 0, overlap = 0;
        Set<String> seen = new HashSet<>();
        Long previous = null;
        Map<Long, List<double[]>> byMaterial = new HashMap<>();
        for (MixPlanner.Segment segment : segments) {
            String key = segment.getMaterialId() + "@" + segment.getSourceStart() + "+" + segment.getDuration();
            if (!seen.add(key)) exact++;
            if (previous != null && previous.equals(segment.getMaterialId())) adjacent++;
            if (segment.getSourceDuration() > 0) {
                double start = segment.getSourceStart();
                double end = start + segment.getDuration();
                List<double[]> intervals = byMaterial.computeIfAbsent(segment.getMaterialId(), k -> new java.util.ArrayList<>());
                boolean hit = false;
                for (double[] iv : intervals) {
                    if (start < iv[1] && end > iv[0]) { hit = true; break; }
                }
                if (hit) overlap++;
                intervals.add(new double[]{start, end});
            }
            previous = segment.getMaterialId();
        }
        if (exact > 0) duplicate.issue("有 " + exact + " 段内容完全重复");
        if (adjacent > 0) duplicate.issue("有 " + adjacent + " 处相邻片段来自同一素材");
        if (overlap > 0) duplicate.issue("有 " + overlap + " 处同源片段时间重叠");
        if (exact == 0 && adjacent == 0 && overlap == 0) {
            duplicate.check("时间线无重复或同源重叠片段");
        } else {
            // 严格交付模式下重复风险从提示升级为硬拦截，杜绝自动产线交付重复画面。
            duplicate.setStatus(strict ? "fail" : "warn");
            duplicate.check("时间线存在重复或同源重叠风险");
        }
    }

    private void assessSemantic(DeliveryQc report, MixPlanner.Plan plan, boolean strict) {
        DeliveryQc.CategoryResult semantic = report.category("semantic");
        int semanticMaterials = plan == null ? 0 : plan.getSemanticSegmentCount();
        int gridMaterials = plan == null ? 0 : plan.getGridFallbackCount();
        if (gridMaterials > 0) {
            boolean noStructuredVisualEvidence = semanticMaterials == 0;
            semantic.setStatus(strict && noStructuredVisualEvidence ? "fail" : "warn");
            semantic.issue(gridMaterials + " 条素材缺少结构化镜头分析，已回退网格切片（降级）"
                    + (strict && noStructuredVisualEvidence ? "；严格交付已拦截，请完成镜头分析或补充可分析素材" : ""));
        }
        semantic.check("语义镜头 " + semanticMaterials + " 条，网格回退 " + gridMaterials + " 条");
    }

    private void assessHook(DeliveryQc report, MixPlanner.Plan plan, boolean hookBurned, boolean strict) {
        DeliveryQc.CategoryResult hook = report.category("hook");
        String strategy = plan == null ? null : plan.getHookStrategy();
        boolean hasText = plan != null && plan.getHookText() != null && !plan.getHookText().isBlank();
        if (!hasText) {
            // 严格交付模式下缺失钩子文案从提示升级为硬拦截，保证自动出片开头必带钩子。
            hook.setStatus(strict ? "fail" : "warn");
            hook.issue("缺少钩子文案，开头吸引力不足");
        }
        hook.check("钩子策略 " + (strategy == null || strategy.isBlank() ? "未设置" : strategy));
        hook.check(hasText ? (hookBurned ? "钩子文案已烧录" : "钩子文案未烧录") : "未生成钩子文案");
    }

    // ---------------- exposure / rhythm / subtitleSync 商业交付维度（提示性，不拦截） ----------------

    /** 商品有效露出：成片中 product 角色素材是否真正出现、露出时长是否达标。 */
    private void assessBrandExposure(DeliveryQc report, MixPlanner.Plan plan) {
        DeliveryQc.CategoryResult exposure = report.category("exposure");
        List<MixPlanner.Segment> segments = plan == null ? List.of() : plan.getSegments();
        List<MixPlanner.Segment> productSegs = segments.stream()
                .filter(s -> "product".equals(s.getSlot())).toList();
        double productSec = productSegs.stream().mapToDouble(MixPlanner.Segment::getDuration).sum();
        double totalSec = segments.stream().mapToDouble(MixPlanner.Segment::getDuration).sum();
        if (productSegs.isEmpty()) {
            exposure.check("无商品/产品段素材（按需配置）");
            return;
        }
        if (productSec < 1.0) {
            exposure.setStatus("warn");
            exposure.issue("存在 " + productSegs.size() + " 段商品素材但成片有效露出不足 1 秒，商品未真正露出");
            return;
        }
        double ratio = totalSec > 0 ? productSec / totalSec : 0;
        exposure.check("商品有效露出 " + FfmpegTool.trimNum(productSec) + "s（占成片 "
                + Math.round(ratio * 100) + "%）");
        if (ratio < 0.05 && totalSec > 10) {
            exposure.setStatus("warn");
            exposure.issue("商品露出占比过低（<5%），可能达不到甲方曝光要求");
        }
    }

    /** 节奏：片段时长分布是否过碎/拖沓/单薄。 */
    private void assessRhythm(DeliveryQc report, MixPlanner.Plan plan) {
        DeliveryQc.CategoryResult rhythm = report.category("rhythm");
        List<MixPlanner.Segment> segments = plan == null ? List.of() : plan.getSegments();
        if (segments.isEmpty()) {
            rhythm.check("无片段可评估");
            return;
        }
        long tooShort = segments.stream().filter(s -> s.getDuration() > 0 && s.getDuration() < 1.0).count();
        long tooLong = segments.stream().filter(s -> s.getDuration() > 25.0).count();
        double avg = segments.stream().mapToDouble(MixPlanner.Segment::getDuration).average().orElse(0);
        rhythm.check(segments.size() + " 个片段，平均 " + FfmpegTool.trimNum(avg) + "s");
        if (tooShort > 0) {
            rhythm.setStatus("warn");
            rhythm.issue(tooShort + " 个片段短于 1 秒，节奏过碎");
        }
        if (tooLong > 0) {
            rhythm.setStatus("warn");
            rhythm.issue(tooLong + " 个片段超过 25 秒，节奏拖沓");
        }
        if (segments.size() < 3) {
            rhythm.setStatus("warn");
            rhythm.issue("仅 " + segments.size() + " 个片段，内容单薄");
        }
    }

    /** 字幕准确同步：AI 配音字幕时间轴是否在成片范围内、有无倒置/重叠。 */
    private void assessSubtitleSync(DeliveryQc report, MixPlanner.Plan plan, double videoDuration, boolean strict) {
        DeliveryQc.CategoryResult sync = report.category("subtitleSync");
        List<MixPlanner.Plan.CaptionCue> cues = plan == null || plan.getNarrationCaptions() == null
                ? List.of() : plan.getNarrationCaptions();
        if (cues.isEmpty()) {
            sync.check("无 AI 配音字幕时间轴可校验（素材转录字幕由渲染侧逐条映射）");
            return;
        }
        long outOfRange = cues.stream()
                .filter(c -> c.getStart() < 0 || c.getEnd() > videoDuration + 0.5).count();
        long inverted = cues.stream().filter(c -> c.getEnd() <= c.getStart()).count();
        List<MixPlanner.Plan.CaptionCue> sorted = new java.util.ArrayList<>(cues);
        sorted.sort(java.util.Comparator.comparingDouble(MixPlanner.Plan.CaptionCue::getStart));
        long overlaps = 0;
        double lastEnd = -1;
        for (MixPlanner.Plan.CaptionCue cue : sorted) {
            if (cue.getStart() < lastEnd - 0.05) overlaps++;
            lastEnd = Math.max(lastEnd, cue.getEnd());
        }
        sync.check("AI 配音字幕 " + cues.size() + " 条已校验时间轴");
        // 严格交付模式下字幕错位从提示升级为硬拦截，自动产线不允许交付对不上画面的字幕。
        if (outOfRange > 0) {
            sync.setStatus(strict ? "fail" : "warn");
            sync.issue(outOfRange + " 条字幕超出成片时长范围，字幕与画面不同步");
        }
        if (inverted > 0) {
            sync.setStatus(strict ? "fail" : "warn");
            sync.issue(inverted + " 条字幕时间倒置");
        }
        if (overlaps > 0) {
            sync.setStatus(strict ? "fail" : "warn");
            sync.issue(overlaps + " 处字幕时间重叠");
        }
    }
    // ---------------- 工具 ----------------

    private static String warn(String current) {
        return "fail".equals(current) ? current : "warn";
    }

    private static double durationTolerance(double duration) {
        return Math.max(3.0, duration * 0.10);
    }
}
