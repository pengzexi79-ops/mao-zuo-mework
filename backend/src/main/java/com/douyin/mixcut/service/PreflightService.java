package com.douyin.mixcut.service;

import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.dto.AdmissionSnapshot;
import com.douyin.mixcut.dto.PreflightIssue;
import com.douyin.mixcut.dto.PreflightResult;
import com.douyin.mixcut.dto.AudioContract;
import com.douyin.mixcut.dto.AudioPreflightResult;
import com.douyin.mixcut.external.ProcessRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.List;

/**
 * Explains whether a planner result can enter the rendering queue.
 * This service deliberately has no repository or process side effects: the dry-run plan is
 * the source of truth for the current material scope, while later modules can add gap data.
 */
@Service
public class PreflightService {

    private final AudioContractService defaultAudioContractService;

    public PreflightService() {
        this.defaultAudioContractService = null;
    }

    @Autowired
    public PreflightService(AudioContractService audioContractService) {
        this.defaultAudioContractService = audioContractService;
    }

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

        String audioMode = params.getAudioMode() == null ? "material-audio" : params.getAudioMode();
        boolean externalAudioRequired = safePlan.isRequiresExternalAudio()
                && ("material-audio".equalsIgnoreCase(audioMode) || "ai-voice".equalsIgnoreCase(audioMode));
        if (externalAudioRequired) {
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

        int semanticSegments = safePlan.getSemanticSegmentCount();
        int gridSegments = safePlan.getGridFallbackCount();
        if (gridSegments > 0) {
            String detail = gridSegments + " 个时间线镜头使用网格回退";
            if (params.getStrictDelivery() && safePlan.isSemanticAnalysisAvailable() && semanticSegments == 0) {
                result.getBlockers().add(PreflightIssue.blocker(
                        "semantic.scene_missing", "semantic", detail + "；严格交付要求确实存在 scene 语义证据。",
                        "analyze_materials"));
            } else {
                result.getWarnings().add(PreflightIssue.warning(
                        "semantic.grid_fallback", "semantic", detail + (safePlan.isSemanticAnalysisAvailable()
                                ? "；保留 role/时长回退。" : "；分析不可用，未将其升级为硬拦截。"), "analyze_materials"));
            }
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

    public PreflightResult evaluateAudio(MixPlanner.Plan plan, MixParams input,
                                         AudioContractService contracts,
                                         ProcessRegistry.CancellationContext context) {
        PreflightResult result = evaluate(plan, input, true, true);
        attachAudioContract(result, plan, input, contracts, context);
        finish(result);
        return result;
    }

    public PreflightResult attachAudioContract(PreflightResult result, MixPlanner.Plan plan,
                                               MixParams input,
                                               AudioContractService contracts,
                                               ProcessRegistry.CancellationContext context) {
        if (result == null) return null;
        MixParams params = input == null ? new MixParams().normalized() : input.normalized();
        MixPlanner.Plan safePlan = plan == null ? new MixPlanner.Plan() : plan;
        AudioPreflightResult audio = new AudioPreflightResult();
        String mode = params.getAudioMode() == null ? "material-audio" : params.getAudioMode().toLowerCase(Locale.ROOT);
        audio.setMode(mode);
        boolean hasBgm = has(safePlan.getBgmPath());
        boolean hasVoice = has(safePlan.getVoicePath());
        audio.setBgmPresent(hasBgm);
        audio.setVoicePresent(hasVoice);
        audio.setOriginalAudioPresent("original".equals(mode));
        audio.setCoverageStatus(result.getAudioCoverageStatus());
        if ("silent".equals(mode) || "original".equals(mode)) {
            audio.setCoverageStatus("not_required");
        } else if (contracts != null && (hasBgm || hasVoice)) {
            if (hasVoice) inspect(audio, true, safePlan.getVoicePath(), safePlan.getVoiceDurationSec(), contracts, context);
            if (hasBgm) inspect(audio, false, safePlan.getBgmPath(), safePlan.getPlannedSec(), contracts, context);
        } else if (("material-audio".equals(mode) || "ai-voice".equals(mode)) && result.getBlockers().stream().noneMatch(issue -> issue.getCode().startsWith("audio."))) {
            audio.getBlockers().add(PreflightIssue.blocker("audio.contract_unavailable", "audio",
                    "当前音频模式没有可执行的音频合同检查，无法安全提交。", "inspect_audio"));
        }
        result.getBlockers().addAll(audio.getBlockers());
        result.getWarnings().addAll(audio.getWarnings());
        audio.getBlockers().stream().map(PreflightIssue::getAction).filter(this::has).distinct().forEach(result.getActions()::add);
        audio.getWarnings().stream().map(PreflightIssue::getAction).filter(this::has).distinct().forEach(result.getActions()::add);
        audio.setStatus(audio.getBlockers().isEmpty()
                ? (audio.getWarnings().isEmpty() ? PreflightResult.READY : PreflightResult.WARNING)
                : PreflightResult.BLOCKED);
        result.setAudio(audio);
        finish(result);
        return result;
    }

    private void inspect(AudioPreflightResult audio, boolean voice, String path, double required,
                         AudioContractService contracts, ProcessRegistry.CancellationContext context) {
        try {
            AudioContract contract = contracts.inspect(path, required, voice ? "voice" : "bgm",
                    context == null ? ProcessRegistry.CancellationContext.none() : context);
            if (voice) audio.setVoiceContract(contract); else audio.setBgmContract(contract);
            List<String> codes = contracts.validate(contract, required);
            audio.getContractCodes().addAll(codes);
            for (String code : codes) {
                audio.getBlockers().add(PreflightIssue.blocker("audio.contract." + code.toLowerCase(Locale.ROOT),
                        "audio", code, "choose_bgm"));
            }
        } catch (RuntimeException ex) {
            audio.getContractCodes().add("AUDIO_CONTRACT_INSPECT_FAILED");
            audio.getBlockers().add(PreflightIssue.blocker("audio.contract_inspect_failed", "audio",
                    "无法读取" + (voice ? "口播" : "背景音乐") + "音频合同。", "choose_bgm"));
        }
    }

    private void finish(PreflightResult result) {
        if (!result.getBlockers().isEmpty()) result.setStatus(PreflightResult.BLOCKED);
        else if (!result.getWarnings().isEmpty()) result.setStatus(PreflightResult.WARNING);
        else result.setStatus(PreflightResult.READY);
    }

    private boolean has(String value) { return value != null && !value.isBlank(); }

    public PreflightResult attachAdmission(PreflightResult result, AdmissionSnapshot admission) {
        if (result != null) result.setAdmission(admission);
        return result;
    }

    private double number(double value, Number fallback) {
        return value > 0 ? value : fallback == null ? 0 : fallback.doubleValue();
    }
}
