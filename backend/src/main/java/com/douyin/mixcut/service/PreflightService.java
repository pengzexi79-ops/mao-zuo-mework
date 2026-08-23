package com.douyin.mixcut.service;

import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.dto.PreflightIssue;
import com.douyin.mixcut.dto.PreflightResult;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Explains whether a planner result can enter the rendering queue.
 * This service deliberately has no repository or process side effects: the dry-run plan is
 * the source of truth for the current material scope, while later modules can add gap data.
 */
@Service
public class PreflightService {

    public PreflightResult evaluate(MixPlanner.Plan plan, MixParams input, boolean ffmpegReady, boolean ffprobeReady) {
        MixParams params = input == null ? new MixParams().normalized() : input.normalized();
        MixPlanner.Plan safePlan = plan == null ? new MixPlanner.Plan() : plan;
        PreflightResult result = new PreflightResult();
        result.setPlan(safePlan);
        result.setPlannedSec(safePlan.getPlannedSec());
        result.setMinSec(number(safePlan.getMinSec(), params.getMinSec()));
        result.setMaxSec(number(params.getMaxSec(), 150));
        result.setTargetSec(number(safePlan.getTargetSec(), result.getMinSec()));
        result.setPlanUsable(safePlan.isUsable());
        result.setInternallyUnique(safePlan.isInternallyUnique());
        result.setRequiresExternalAudio(safePlan.isRequiresExternalAudio());
        result.setVisualCount((int) safePlan.getSegments().stream()
                .filter(segment -> segment != null && segment.getMaterialId() != null).count());
        result.setTotalVisualCount(result.getVisualCount());
        result.setUsableVisualSec(safePlan.getSegments().stream()
                .filter(segment -> segment != null)
                .mapToDouble(MixPlanner.Segment::getDuration)
                .sum());
        result.setExcludedVisualSec(0);

        if (!ffmpegReady || !ffprobeReady) {
            result.getBlockers().add(PreflightIssue.blocker(
                    "runtime.media_tools_unavailable", "runtime",
                    "未检测到媒体处理引擎或媒体探测工具，无法安全开始出片。",
                    "inspect_runtime"));
        }

        if (safePlan.getSegments().isEmpty()) {
            result.getBlockers().add(PreflightIssue.blocker(
                    "plan.empty", "capacity", "当前素材范围没有排出可用画面片段。请补充可读素材或调整素材范围。",
                    "import_material"));
        }

        double planned = safePlan.getPlannedSec();
        if (planned < result.getMinSec()) {
            result.getBlockers().add(PreflightIssue.blocker(
                    "duration.below_min", "duration",
                    String.format(Locale.ROOT, "计划实际 %.1f 秒，低于交付下限 %.1f 秒。", planned, result.getMinSec()),
                    "import_material"));
        } else if (planned > result.getMaxSec()) {
            result.getBlockers().add(PreflightIssue.blocker(
                    "duration.above_max", "duration",
                    String.format(Locale.ROOT, "计划实际 %.1f 秒，超过交付上限 %.1f 秒。", planned, result.getMaxSec()),
                    "rerun_preview"));
        } else if (result.getTargetSec() > 0 && planned + 0.05 < result.getTargetSec()) {
            result.getWarnings().add(PreflightIssue.warning(
                    "duration.below_target", "duration",
                    String.format(Locale.ROOT, "计划实际 %.1f 秒，低于推荐目标 %.1f 秒，但仍在交付区间内。", planned, result.getTargetSec()),
                    "rerun_preview"));
        }

        if (!safePlan.isInternallyUnique()) {
            String detail = safePlan.getNotes().stream()
                    .filter(note -> note != null && (note.contains("完全重复") || note.contains("同源重叠")))
                    .findFirst()
                    .orElse("当前计划存在重复或同源重叠片段");
            if ("off".equalsIgnoreCase(params.getDedupStrictness())) {
                result.getWarnings().add(PreflightIssue.warning(
                        "dedupe.opted_out", "dedupe", detail + "；当前已明确关闭去重校验。",
                        "rerun_preview"));
            } else {
                result.getBlockers().add(PreflightIssue.blocker(
                        "dedupe.conflict", "dedupe", detail + "；请补充素材或放宽去重策略后重新预览。",
                        "relax_dedupe"));
            }
        }

        if (safePlan.isRequiresExternalAudio()) {
            boolean hasBgm = safePlan.getBgmPath() != null && !safePlan.getBgmPath().isBlank();
            boolean hasVoice = safePlan.getVoicePath() != null && !safePlan.getVoicePath().isBlank();
            if (!hasBgm && !hasVoice) {
                result.setAudioCoverageStatus("missing_source");
                result.getBlockers().add(PreflightIssue.blocker(
                        "audio.missing_source", "audio",
                        "当前素材音轨模式没有可用的背景音乐或口播音频。",
                        "choose_bgm"));
            } else if (!hasBgm && safePlan.getVoiceDurationSec() + 0.5 < planned) {
                result.setAudioCoverageStatus("insufficient_voice");
                result.getBlockers().add(PreflightIssue.blocker(
                        "audio.insufficient_voice", "audio",
                        String.format(Locale.ROOT, "口播仅 %.1f 秒，无法覆盖计划 %.1f 秒；请选择背景音乐覆盖剩余时间。",
                                safePlan.getVoiceDurationSec(), planned),
                        "choose_bgm"));
            } else {
                result.setAudioCoverageStatus("ready");
            }
        } else {
            result.setAudioCoverageStatus("not_required");
        }

        if (!safePlan.getNotes().isEmpty()) {
            safePlan.getNotes().stream()
                    .filter(note -> note != null && (note.contains("相邻片段") || note.contains("未标注")))
                    .forEach(note -> result.getWarnings().add(PreflightIssue.warning(
                            "plan.quality_note", "quality", note, "rerun_preview")));
        }

        result.getBlockers().stream().map(PreflightIssue::getAction).filter(action -> action != null && !action.isBlank())
                .distinct().forEach(result.getActions()::add);
        result.getWarnings().stream().map(PreflightIssue::getAction).filter(action -> action != null && !action.isBlank())
                .distinct().forEach(result.getActions()::add);
        if (!result.getBlockers().isEmpty()) result.setStatus(PreflightResult.BLOCKED);
        else if (!result.getWarnings().isEmpty()) result.setStatus(PreflightResult.WARNING);
        else result.setStatus(PreflightResult.READY);
        return result;
    }

    private double number(double value, Number fallback) {
        return value > 0 ? value : fallback == null ? 0 : fallback.doubleValue();
    }
}
