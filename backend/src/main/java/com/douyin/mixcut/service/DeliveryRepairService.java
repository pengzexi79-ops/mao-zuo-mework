package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.DeliveryQc;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.MaterialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 把确定性 QC 结果转换为可执行修复策略。AI 只提供建议，所有策略都要经过本地资源与参数校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryRepairService {
    private final MaterialStore materialStore;
    private final AiService aiService;
    private final FfmpegTool ffmpeg;
    private final AppProps props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class RepairAssessment {
        private String status = "no_action";
        private String issueId;
        private String category;
        private String severity;
        private String evidence;
        private boolean autoFixable;
        private String recommendedAction;
        private List<String> candidateActions = new ArrayList<>();
        private String aiAssessment;
        private String reason;
        private boolean improved;

        public boolean needsHuman() { return "awaiting_decision".equals(status); }
    }

    /** 先使用本地事实，再在存在多种合理方案时请求 AI。 */
    public RepairAssessment assess(RenderService.RenderResult result, MixParams params,
                                   MixPlanner.Plan plan, int iteration) {
        RepairAssessment assessment = new RepairAssessment();
        String qcJson = result == null ? null : result.getQcJson();
        DeliveryQc report = parseQc(qcJson);
        DeliveryQc.CategoryResult failing = firstFailing(report);
        String error = result == null ? "" : String.valueOf(result.getError());
        if (failing == null && error.isBlank()) {
            assessment.setStatus("no_action");
            return assessment;
        }

        String category = failing == null ? inferCategory(error) : failing.getCategory();
        String issue = failing == null || failing.getIssues().isEmpty() ? error : failing.getIssues().get(0);
        assessment.setCategory(category);
        assessment.setIssueId(issueId(category, issue));
        assessment.setSeverity(isTechnical(category) ? "P1" : "P2");
        assessment.setEvidence(issue);
        if (isNonRepairableExecutionFailure(error)) {
            assessment.setStatus("awaiting_decision");
            assessment.setSeverity("P0");
            assessment.setEvidence(error);
            assessment.setReason("当前运行环境或任务状态不允许自动修改媒体计划");
            assessment.getCandidateActions().add("await-human-edit-choice");
            return assessment;
        }
        assessment.setCandidateActions(candidatesFor(category, issue, params, plan));
        String recommended = recommend(category, issue, params, plan);
        assessment.setRecommendedAction(recommended);
        assessment.setAutoFixable(recommended != null && !recommended.isBlank()
                && iteration < Math.max(0, props.getMaxRepairIterations()));
        assessment.setReason(assessment.isAutoFixable() ? "存在经过本地资源校验的自动修复候选" : "缺少安全的自动修复条件，需要人工选择");
        if (!assessment.isAutoFixable()) assessment.setStatus("awaiting_decision");

        if (!assessment.isAutoFixable() || assessment.getCandidateActions().size() > 1) {
            JsonNode ai = askAi(category, issue, assessment.getCandidateActions(), params, plan);
            if (ai != null) {
                assessment.setAiAssessment(ai.path("assessment").asText(ai.path("reason").asText("")));
                String aiAction = ai.path("recommendedAction").asText("");
                // Deterministic local media checks are authoritative. AI may explain or rank
                // candidates, but cannot downgrade an executable repair to human-only handling.
                if (assessment.getCandidateActions().contains(aiAction)
                        && isSafeAiOverride(assessment.getRecommendedAction(), aiAction)) {
                    assessment.setRecommendedAction(aiAction);
                }
            }
        }
        return assessment;
    }

    /** 对本地可读 BGM 做确定性筛选，排除损坏、无音轨或不存在的文件。 */
    public Optional<Material> findReadableBgm(Long excludedId) {
        return materialStore.findByFileType(Material.FileType.audio).stream()
                .filter(m -> m.getId() == null || !Objects.equals(m.getId(), excludedId))
                .filter(m -> m.getRole() == MaterialRole.bgm || m.getRole() == MaterialRole.none)
                .filter(m -> m.getStatus() != Material.Status.failed)
                .filter(m -> m.getFilePath() != null && Files.isRegularFile(Path.of(m.getFilePath())))
                .filter(this::hasReadableAudio)
                .findFirst();
    }

    public List<Material> readableBgms() {
        return materialStore.findByFileType(Material.FileType.audio).stream()
                .filter(m -> m.getRole() == MaterialRole.bgm || m.getRole() == MaterialRole.none)
                .filter(this::isReadableBgm)
                .toList();
    }

    public boolean isReadableBgm(Material material) {
        return material != null
                && material.getFileType() == Material.FileType.audio
                && material.getStatus() != Material.Status.failed
                && material.getFilePath() != null
                && Files.isRegularFile(Path.of(material.getFilePath()))
                && hasReadableAudio(material);
    }

    private boolean hasReadableAudio(Material material) {
        try {
            FfmpegTool.MediaInfo info = ffmpeg.probe(material.getFilePath());
            return info != null && info.isHasAudio() && info.getAudioDuration() > 0;
        } catch (Exception e) {
            log.debug("repair candidate audio cannot be probed: {}", e.toString());
            return false;
        }
    }

    public boolean applyAutomatic(MixParams params, RepairAssessment assessment) {
        if (params == null || assessment == null || !assessment.isAutoFixable()) return false;
        String action = assessment.getRecommendedAction();
        if ("replace-bgm".equals(action)) {
            Optional<Material> bgm = findReadableBgm(params.getBgmMaterialId());
            if (bgm.isEmpty()) return false;
            params.setBgmMaterialId(bgm.get().getId());
            params.setAudioMode("material-audio");
            return true;
        }
        if ("switch-hook".equals(action)) {
            params.setHookStrategy(null);
            params.setAutoRehook(false);
            return true;
        }
        if ("relax-subtitle".equals(action)) {
            params.setAutoSubtitles(false);
            params.setBurnAiVoiceCaptions(false);
            return true;
        }
        if ("keep-original-audio".equals(action)) {
            params.setAudioMode("original");
            return true;
        }
        if ("regenerate-plan".equals(action)) {
            params.setSeed(System.nanoTime());
            return true;
        }
        return false;
    }

    private DeliveryQc parseQc(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return objectMapper.readValue(raw, DeliveryQc.class); } catch (Exception e) { return null; }
    }

    private DeliveryQc.CategoryResult firstFailing(DeliveryQc report) {
        if (report == null || report.getCategories() == null) return null;
        return report.getCategories().stream().filter(c -> "fail".equals(c.getStatus())).findFirst().orElse(null);
    }

    private String inferCategory(String error) {
        String text = error == null ? "" : error;
        if (text.contains("音频") || text.contains("音轨") || text.contains("混音")) return "audio";
        if (text.contains("字幕")) return "subtitle";
        if (text.contains("重复") || text.contains("同源")) return "duplicate";
        if (text.contains("黑屏") || text.contains("画面") || text.contains("视频")) return "video";
        return "general";
    }

    private boolean isTechnical(String category) { return Set.of("audio", "video", "subtitle").contains(category); }

    private String issueId(String category, String issue) {
        String key = (category + ":" + issue).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-");
        return key.length() > 60 ? key.substring(0, 60) : key;
    }

    private List<String> candidatesFor(String category, String issue, MixParams params, MixPlanner.Plan plan) {
        List<String> out = new ArrayList<>();
        if ("audio".equals(category)) {
            if (findReadableBgm(params == null ? null : params.getBgmMaterialId()).isPresent()) out.add("replace-bgm");
            if (hasReadableOriginalAudio(plan)) out.add("keep-original-audio");
            out.add("await-human-audio-choice");
        } else if ("subtitle".equals(category)) {
            out.add("relax-subtitle");
            out.add("await-human-subtitle-choice");
        } else if ("video".equals(category) || "duplicate".equals(category) || "hook".equals(category)) {
            out.add("regenerate-plan");
            if ("hook".equals(category)) out.add("switch-hook");
            out.add("await-human-edit-choice");
        } else {
            out.add("regenerate-plan");
            out.add("await-human-edit-choice");
        }
        return out;
    }

    private String recommend(String category, String issue, MixParams params, MixPlanner.Plan plan) {
        if ("audio".equals(category)) {
            return findReadableBgm(params == null ? null : params.getBgmMaterialId()).isPresent() ? "replace-bgm" : null;
        }
        if ("subtitle".equals(category)) return "relax-subtitle";
        if ("hook".equals(category)) return "switch-hook";
        if ("video".equals(category) || "duplicate".equals(category)) return "regenerate-plan";
        return null;
    }

    private boolean isSafeAiOverride(String deterministicAction, String aiAction) {
        // A deterministic recommendation exists only after local readability, ownership and
        // task-parameter checks. Keep that execution decision stable; AI supplies rationale only.
        return deterministicAction == null || deterministicAction.isBlank() || deterministicAction.equals(aiAction);
    }

    private boolean hasReadableOriginalAudio(MixPlanner.Plan plan) {
        if (plan == null || plan.getSegments() == null) return false;
        return plan.getSegments().stream()
                .map(MixPlanner.Segment::getFilePath)
                .filter(Objects::nonNull)
                .anyMatch(path -> {
                    try {
                        FfmpegTool.MediaInfo info = ffmpeg.probe(path);
                        return info != null && info.isHasAudio() && info.getAudioDuration() > 0;
                    } catch (Exception ignored) {
                        return false;
                    }
                });
    }

    private boolean isNonRepairableExecutionFailure(String error) {
        String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
        return normalized.contains("找不到 ffmpeg")
                || normalized.contains("超过时限")
                || normalized.contains("已取消")
                || normalized.contains("权限")
                || normalized.contains("被占用");
    }

    private JsonNode askAi(String category, String issue, List<String> candidates, MixParams params, MixPlanner.Plan plan) {
        try {
            return aiService.askJson(com.douyin.mixcut.domain.UseCase.qc,
                    "你是成片质检修复顾问。只返回 JSON，字段为 assessment、recommendedAction、reason。只能从候选动作中选择，不得生成命令或路径。",
                    objectMapper.writeValueAsString(Map.of("category", category, "issue", issue, "candidates", candidates,
                            "audioMode", params == null ? "" : params.getAudioMode(), "plannedSec", plan == null ? 0 : plan.getPlannedSec())),
                    0.2, 400, null);
        } catch (Exception e) {
            log.debug("QC AI assessment unavailable: {}", e.toString());
            return null;
        }
    }
}
