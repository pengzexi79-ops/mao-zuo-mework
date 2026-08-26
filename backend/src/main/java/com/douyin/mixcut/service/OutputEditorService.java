package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.JobOutput;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.OutputEditSession;
import com.douyin.mixcut.domain.OutputVersion;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.JobOutputRepo;
import com.douyin.mixcut.repository.Repositories.OutputEditSessionRepo;
import com.douyin.mixcut.repository.Repositories.OutputVersionRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Owns a single-output editing lifecycle. The browser supplies only material IDs and bounded
 * timeline values; paths and media properties are reloaded from the local material store.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutputEditorService {
    private static final double MIN_VIDEO_SEGMENT_SEC = 0.5;
    private static final double MAX_IMAGE_SEGMENT_SEC = 12;
    private static final double MAX_EDIT_DURATION_SEC = 300;

    private final OutputEditSessionRepo sessionRepo;
    private final OutputVersionRepo versionRepo;
    private final JobOutputRepo outputRepo;
    private final MaterialStore materialStore;
    private final RenderService renderService;
    private final DeliveryRepairService deliveryRepairService;
    private final FfmpegTool ffmpeg;
    private final ObjectMapper om;
    private final OutputEditorPersistenceService persistenceService;
    @Qualifier("renderExecutor") private final Executor renderExecutor;

    @Data
    public static class EditSegment {
        private int index;
        private Long materialId;
        private double sourceStart;
        private double duration;
        private String slot = "body";
        private boolean enabled = true;
    }

    @Data
    public static class AudioEdit {
        private String mode = "original";
        private Long bgmMaterialId;
        private Long voiceMaterialId;
        private double bgmVolume = 0.20;
        private double originalAudioVolume = 1.0;
    }

    @Data
    public static class SubtitleEdit {
        private boolean enabled;
        private boolean cleanSourceSubtitles;
        private String safeBandMode = "off";
    }

    @Data
    public static class EditRequest {
        private List<EditSegment> segments = new ArrayList<>();
        private AudioEdit audio = new AudioEdit();
        private SubtitleEdit subtitle = new SubtitleEdit();
        private String comment;
    }

    @Data
    public static class EditorState {
        private OutputEditSession session;
        private OutputVersion baseVersion;
        private OutputVersion candidateVersion;
        private MixPlanner.Plan plan;
        private MixParams params;
        private List<Map<String, Object>> materials = new ArrayList<>();
        private List<Map<String, Object>> readableBgms = new ArrayList<>();
        private List<Map<String, Object>> readableVoices = new ArrayList<>();
        private List<Map<String, Object>> publicSourceSegments = new ArrayList<>();
    }

    @Transactional
    public EditorState open(Long jobId, int idx) {
        OutputVersion base = latestUsableVersion(jobId, idx);
        OutputEditSession session = sessionRepo.findTopByJobIdAndIdxOrderByUpdatedAtDesc(jobId, idx)
                .filter(existing -> !"applied".equals(existing.getStatus()))
                .orElseGet(() -> createSession(jobId, idx, base));
        recoverStaleRenderingSession(session);
        return state(session, base);
    }

    public void verifySession(Long sessionId, Long jobId, int idx) {
        OutputEditSession session = session(sessionId);
        if (!jobId.equals(session.getJobId()) || session.getIdx() == null || session.getIdx() != idx) {
            throw new IllegalArgumentException("编辑会话不属于当前成片");
        }
    }

    @Transactional
    public EditorState save(Long sessionId, EditRequest request) {
        OutputEditSession session = session(sessionId);
        if ("rendering".equals(session.getStatus())) throw new IllegalArgumentException("候选正在渲染，请等待结束后再修改草稿");
        MixParams params = buildParams(session, request);
        MixPlanner.Plan plan = buildPlan(session, request, params);
        resolvePlanAudio(plan, params);
        session.setPlanSnapshot(write(plan));
        session.setParamsSnapshot(write(params));
        session.setComment(limit(request == null ? null : request.getComment(), 500));
        session.setError(null);
        session.setStatus("draft");
        session.setCandidateVersionId(null);
        sessionRepo.save(session);
        return state(session, latestUsableVersion(session.getJobId(), session.getIdx()));
    }

    @Transactional
    public EditorState render(Long sessionId) {
        OutputEditSession session = session(sessionId);
        if ("rendering".equals(session.getStatus())) throw new IllegalArgumentException("候选正在渲染");
        MixPlanner.Plan plan = readPlan(session.getPlanSnapshot());
        MixParams params = readParams(session.getParamsSnapshot());
        resolvePlanAudio(plan, params);
        validatePlan(plan, params);
        session.setStatus("rendering");
        session.setError(null);
        sessionRepo.save(session);
        try {
            renderExecutor.execute(() -> renderCandidate(sessionId));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            session.setStatus("failed");
            session.setError("候选渲染队列繁忙，请稍后重新生成候选");
            sessionRepo.save(session);
            throw new IllegalArgumentException(session.getError());
        }
        return state(session, latestUsableVersion(session.getJobId(), session.getIdx()));
    }

    @Transactional
    public EditorState apply(Long sessionId, boolean confirm) {
        if (!confirm) throw new IllegalArgumentException("请确认后再将候选设为当前成片");
        OutputEditSession session = session(sessionId);
        if (!"passed".equals(session.getStatus()) || session.getCandidateVersionId() == null) {
            throw new IllegalArgumentException("只有通过完整质检的编辑候选可以应用为当前成片");
        }
        OutputVersion candidate = versionRepo.findById(session.getCandidateVersionId())
                .orElseThrow(() -> new IllegalArgumentException("候选版本不存在"));
        if (!"passed".equals(candidate.getStatus()) || candidate.getFilePath() == null || candidate.getFilePath().isBlank()) {
            throw new IllegalArgumentException("候选版本未通过质检，不能替换当前成片");
        }
        JobOutput output = outputRepo.findByJobIdAndIdx(session.getJobId(), session.getIdx())
                .orElseGet(JobOutput::new);
        // Preserve the old candidate in history but make the delivery pointer unambiguous.
        for (OutputVersion previous : versionRepo.findByJobIdAndIdxOrderByVersionNoAsc(session.getJobId(), session.getIdx())) {
            if (previous.getId() != null && !previous.getId().equals(candidate.getId())
                    && output.getId() != null && output.getId().equals(previous.getJobOutputId())) {
                previous.setJobOutputId(null);
                if ("passed".equals(previous.getStatus())) previous.setStatus("rolled_back");
                versionRepo.save(previous);
            }
        }
        output.setJobId(session.getJobId());
        output.setIdx(session.getIdx());
        output.setFilePath(candidate.getFilePath());
        output.setDurationSec(candidate.getDurationSec());
        output.setThumbnail(candidate.getThumbnail());
        output.setQcStatus("pass");
        output.setQcReport(candidate.getQcReport());
        output.setQcJson(candidate.getQcJson());
        output.setUsedMaterials(candidate.getUsedMaterials());
        output.setDowngradeInfo("[\"已由可编辑出片工作台确认应用\"]");
        JobOutput saved = outputRepo.save(output);
        candidate.setJobOutputId(saved.getId());
        candidate.setStatus("passed");
        versionRepo.save(candidate);
        session.setStatus("applied");
        sessionRepo.save(session);
        return state(session, candidate);
    }

    private void renderCandidate(Long sessionId) {
        try {
            OutputEditSession session = session(sessionId);
            MixPlanner.Plan plan = readPlan(session.getPlanSnapshot());
            MixParams params = readParams(session.getParamsSnapshot());
            validatePlan(plan, params);
            String name = "edit_" + session.getJobId() + "_" + String.format("%02d", session.getIdx()) + "_" + System.currentTimeMillis();
            RenderService.RenderResult result = renderService.render(plan, params, name,
                    step -> log.info("editor session {}: {}", sessionId, step), Instant.now().plusSeconds(1800));
            persistenceService.persistCandidate(sessionId, plan, params, result);
        } catch (Exception e) {
            log.warn("editor session {} render failed: {}", sessionId, e.toString());
            persistenceService.failSession(sessionId, "候选渲染失败：" + concise(e));
        }
    }

    private void recoverStaleRenderingSession(OutputEditSession session) {
        if (!"rendering".equals(session.getStatus()) || session.getUpdatedAt() == null) return;
        if (session.getUpdatedAt().isBefore(java.time.LocalDateTime.now().minusMinutes(35))) {
            session.setStatus("failed");
            session.setError("候选渲染未完成，可能因应用重启或任务中断；请检查草稿后重新生成候选");
            sessionRepo.save(session);
        }
    }

    private OutputEditSession createSession(Long jobId, int idx, OutputVersion base) {
        OutputEditSession session = new OutputEditSession();
        session.setJobId(jobId);
        session.setIdx(idx);
        session.setBaseVersionId(base == null ? null : base.getId());
        session.setPlanSnapshot(base == null ? null : base.getPlanSnapshot());
        session.setParamsSnapshot(base == null ? null : base.getParamsSnapshot());
        session.setStatus("draft");
        return sessionRepo.save(session);
    }

    private EditorState state(OutputEditSession session, OutputVersion fallbackBase) {
        EditorState state = new EditorState();
        state.setSession(session);
        OutputVersion base = session.getBaseVersionId() == null ? fallbackBase
                : versionRepo.findById(session.getBaseVersionId()).orElse(fallbackBase);
        state.setBaseVersion(base);
        state.setCandidateVersion(session.getCandidateVersionId() == null ? null
                : versionRepo.findById(session.getCandidateVersionId()).orElse(null));
        MixPlanner.Plan plan = readPlan(session.getPlanSnapshot());
        MixParams params = readParams(session.getParamsSnapshot());
        state.setPlan(plan);
        state.setParams(params);
        state.setMaterials(materials(params));
        state.setReadableBgms(readableBgms(params));
        state.setReadableVoices(readableVoices(params));
        state.setPublicSourceSegments(publicSourceSegments(plan, params));
        return state;
    }

    private MixPlanner.Plan buildPlan(OutputEditSession session, EditRequest request, MixParams params) {
        if (request == null || request.getSegments() == null) throw new IllegalArgumentException("请至少保留一个画面片段");
        MixPlanner.Plan base = readPlan(session.getPlanSnapshot());
        List<EditSegment> edits = request.getSegments().stream()
                .sorted(Comparator.comparingInt(EditSegment::getIndex)).toList();
        if (edits.stream().noneMatch(EditSegment::isEnabled)) {
            throw new IllegalArgumentException("请至少启用一个画面片段");
        }
        List<MixPlanner.Segment> segments = new ArrayList<>();
        int index = 1;
        for (EditSegment edit : edits) {
            MixPlanner.Segment segment = toSegment(edit, index++, params);
            segment.setEnabled(edit.isEnabled());
            segments.add(segment);
        }
        base.setSegments(segments);
        double duration = segments.stream().filter(MixPlanner.Segment::isEnabled)
                .mapToDouble(MixPlanner.Segment::getDuration).sum();
        base.setPlannedSec(duration);
        base.setTargetSec(duration);
        base.setMinSec(Math.min(Math.max(1, base.getMinSec()), duration));
        base.getNotes().add("已由可编辑出片工作台调整 " + segments.size() + " 个片段");
        return base;
    }

    private MixPlanner.Segment toSegment(EditSegment edit, int index, MixParams params) {
        if (edit.getMaterialId() == null) throw new IllegalArgumentException("片段缺少素材");
        Material material = materialStore.findById(edit.getMaterialId())
                .orElseThrow(() -> new IllegalArgumentException("素材不存在或已删除"));
        if (material.getStatus() == Material.Status.failed || material.getFilePath() == null || material.getFilePath().isBlank()) {
            throw new IllegalArgumentException("素材不可读，不能用于编辑候选");
        }
        if (edit.isEnabled() && !MaterialSourcePolicy.allows(material, params)) {
            throw new IllegalArgumentException("当前为仅本地素材模式，公开来源素材必须先替换或移除");
        }
        if (material.getFileType() == Material.FileType.audio) throw new IllegalArgumentException("音频不能作为画面片段插入");
        double start = Math.max(0, edit.getSourceStart());
        double duration = edit.getDuration();
        if (material.getFileType() == Material.FileType.image) {
            if (duration <= 0 || duration > MAX_IMAGE_SEGMENT_SEC) throw new IllegalArgumentException("图片片段时长必须在 0 到 " + MAX_IMAGE_SEGMENT_SEC + " 秒之间");
            start = 0;
        } else {
            double sourceDuration = material.getDurationSec() == null ? 0 : material.getDurationSec();
            if (duration < MIN_VIDEO_SEGMENT_SEC || start + duration > sourceDuration + 0.05) {
                throw new IllegalArgumentException("视频裁剪超出素材范围：" + safeName(material));
            }
        }
        MixPlanner.Segment segment = new MixPlanner.Segment();
        segment.setIndex(index);
        segment.setMaterialId(material.getId());
        segment.setMaterialName(safeName(material));
        segment.setFilePath(material.getFilePath());
        segment.setKind(material.getFileType() == Material.FileType.image ? "image" : "video");
        segment.setSourceStart(start);
        segment.setDuration(duration);
        segment.setSourceDuration(material.getDurationSec() == null ? duration : material.getDurationSec());
        segment.setSlot(validSlot(edit.getSlot()));
        return segment;
    }

    private MixParams buildParams(OutputEditSession session, EditRequest request) {
        MixParams params = readParams(session.getParamsSnapshot());
        AudioEdit audio = request == null || request.getAudio() == null ? new AudioEdit() : request.getAudio();
        String mode = audio.getMode() == null ? "original" : audio.getMode().trim().toLowerCase();
        if ("ai-voice".equals(mode)) mode = "original";
        if (!List.of("original", "material-audio", "silent").contains(mode)) throw new IllegalArgumentException("编辑器不支持该音频模式");
        params.setAudioMode(mode);
        params.setOriginalAudioVolume(clamp(audio.getOriginalAudioVolume(), 0, 1));
        params.setBgmVolume(clamp(audio.getBgmVolume(), 0, 1));
        params.setBgmMaterialId(null);
        params.setVoiceMaterialId(null);
        params.setHookAudioMaterialId(null);
        if ("material-audio".equals(mode)) {
            if (audio.getBgmMaterialId() == null) throw new IllegalArgumentException("背景音乐模式必须选择一条可读 BGM");
            Material bgm = materialStore.findById(audio.getBgmMaterialId()).orElseThrow(() -> new IllegalArgumentException("背景音乐不存在"));
            if (!MaterialSourcePolicy.allows(bgm, params)) throw new IllegalArgumentException("当前为仅本地素材模式，不能选择公开来源背景音乐");
            if (!deliveryRepairService.isReadableBgm(bgm)) throw new IllegalArgumentException("所选背景音乐无法解码或没有有效音轨");
            params.setBgmMaterialId(bgm.getId());
            if (audio.getVoiceMaterialId() != null) {
                Material voice = readableVoice(audio.getVoiceMaterialId(), params);
                params.setVoiceMaterialId(voice.getId());
            }
        }
        SubtitleEdit subtitle = request == null || request.getSubtitle() == null ? new SubtitleEdit() : request.getSubtitle();
        boolean subtitleEnabled = !"silent".equals(mode) && subtitle.isEnabled();
        params.setAutoSubtitles(subtitleEnabled);
        params.setBurnAiVoiceCaptions(subtitleEnabled);
        params.setCleanSourceSubtitles(!"silent".equals(mode) && subtitle.isCleanSourceSubtitles());
        params.setSourceSubtitleCleanMode("subtitle-safe-band".equals(subtitle.getSafeBandMode()) ? "subtitle-safe-band" : "off");
        if ("silent".equals(mode)) {
            params.setBurnHookText(false);
            params.setBurnRehookText(false);
        }
        return params.normalized();
    }

    private void resolvePlanAudio(MixPlanner.Plan plan, MixParams params) {
        plan.setBgmMaterialId(null);
        plan.setBgmPath(null);
        plan.setBgmDurationSec(0);
        plan.setVoiceMaterialId(null);
        plan.setVoicePath(null);
        plan.setVoiceDurationSec(0);
        plan.setHookAudioMaterialId(null);
        plan.setHookAudioPath(null);
        plan.setRequiresExternalAudio(false);
        if ("material-audio".equalsIgnoreCase(params.getAudioMode())) {
            Material bgm = materialStore.findById(params.getBgmMaterialId())
                    .orElseThrow(() -> new IllegalArgumentException("背景音乐不存在"));
            if (!deliveryRepairService.isReadableBgm(bgm)) throw new IllegalArgumentException("背景音乐无法解码或没有有效音轨");
            FfmpegTool.MediaInfo info = ffmpeg.probe(bgm.getFilePath());
            plan.setBgmMaterialId(bgm.getId());
            plan.setBgmPath(bgm.getFilePath());
            plan.setBgmDurationSec(info == null ? 0 : info.getAudioDuration());
            if (params.getVoiceMaterialId() != null) {
                Material voice = readableVoice(params.getVoiceMaterialId(), params);
                FfmpegTool.MediaInfo voiceInfo = ffmpeg.probe(voice.getFilePath());
                plan.setVoiceMaterialId(voice.getId());
                plan.setVoicePath(voice.getFilePath());
                plan.setVoiceDurationSec(voiceInfo == null ? 0 : voiceInfo.getAudioDuration());
            }
            plan.setRequiresExternalAudio(true);
        }
    }

    private void validatePlan(MixPlanner.Plan plan, MixParams params) {
        if (plan == null || plan.getSegments() == null || plan.getSegments().isEmpty()) throw new IllegalArgumentException("编辑计划没有可渲染片段");
        for (MixPlanner.Segment segment : plan.getSegments()) {
            if (!segment.isEnabled()) continue;
            if (segment.getMaterialId() == null) throw new IllegalArgumentException("编辑计划包含没有素材的镜头");
            Material material = materialStore.findById(segment.getMaterialId())
                    .orElseThrow(() -> new IllegalArgumentException("编辑计划引用的素材已删除"));
            if (!MaterialSourcePolicy.allows(material, params)) {
                throw new IllegalArgumentException("当前为仅本地素材模式，公开来源镜头必须替换或移除后才能生成候选");
            }
        }
        double total = plan.getSegments().stream().filter(MixPlanner.Segment::isEnabled)
                .mapToDouble(MixPlanner.Segment::getDuration).sum();
        if (total < 1 || total > MAX_EDIT_DURATION_SEC) throw new IllegalArgumentException("编辑计划总时长必须在 1 到 " + MAX_EDIT_DURATION_SEC + " 秒之间");
        plan.setPlannedSec(total);
        plan.setTargetSec(total);
        plan.setMinSec(Math.min(Math.max(1, plan.getMinSec()), total));
        if (!plan.isUsable()) throw new IllegalArgumentException("编辑计划不满足渲染基础条件");
    }

    private OutputVersion latestUsableVersion(Long jobId, int idx) {
        List<OutputVersion> versions = versionRepo.findByJobIdAndIdxOrderByVersionNoAsc(jobId, idx).stream()
                .filter(v -> v.getPlanSnapshot() != null && !v.getPlanSnapshot().isBlank()).toList();
        Long outputId = outputRepo.findByJobIdAndIdx(jobId, idx).map(JobOutput::getId).orElse(null);
        return versions.stream().filter(v -> outputId != null && outputId.equals(v.getJobOutputId()))
                .max(Comparator.comparing(v -> v.getVersionNo() == null ? 0 : v.getVersionNo()))
                .or(() -> versions.stream().filter(v -> "passed".equals(v.getStatus()))
                        .max(Comparator.comparing(v -> v.getVersionNo() == null ? 0 : v.getVersionNo())))
                .orElseGet(() -> versions.stream()
                        .max(Comparator.comparing(v -> v.getVersionNo() == null ? 0 : v.getVersionNo())).orElse(null));
    }

    private OutputEditSession session(Long id) {
        return sessionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("编辑会话不存在"));
    }

    private MixPlanner.Plan readPlan(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("当前成片没有可编辑的计划快照");
        try { return om.readValue(raw, MixPlanner.Plan.class); }
        catch (Exception e) { throw new IllegalArgumentException("无法读取成片计划快照"); }
    }

    private MixParams readParams(String raw) {
        if (raw == null || raw.isBlank()) return new MixParams().normalized();
        try { return om.readValue(raw, MixParams.class).normalized(); }
        catch (Exception e) { return new MixParams().normalized(); }
    }

    private List<Map<String, Object>> materials(MixParams params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Material material : materialStore.findAll()) {
            if (material.getStatus() == Material.Status.failed || material.getFilePath() == null) continue;
            if (material.getFileType() == Material.FileType.audio || !MaterialSourcePolicy.allows(material, params)) continue;
            rows.add(materialRow(material));
        }
        return rows;
    }

    private List<Map<String, Object>> readableBgms(MixParams params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Material material : deliveryRepairService.readableBgms()) {
            if (!MaterialSourcePolicy.allows(material, params)) continue;
            rows.add(audioRow(material, material.getDurationSec()));
        }
        return rows;
    }

    private List<Map<String, Object>> readableVoices(MixParams params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Material material : materialStore.findByFileType(Material.FileType.audio)) {
            if (material.getStatus() == Material.Status.failed || material.getFilePath() == null || !MaterialSourcePolicy.allows(material, params)) continue;
            FfmpegTool.MediaInfo info = ffmpeg.probe(material.getFilePath());
            if (info == null || !info.isHasAudio() || info.getAudioDuration() <= 0) continue;
            rows.add(audioRow(material, info.getAudioDuration()));
        }
        return rows;
    }

    private List<Map<String, Object>> publicSourceSegments(MixPlanner.Plan plan, MixParams params) {
        if (!MaterialSourcePolicy.localOnly(params) || plan == null || plan.getSegments() == null) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MixPlanner.Segment segment : plan.getSegments()) {
            if (!segment.isEnabled()) continue;
            Material material = segment.getMaterialId() == null ? null : materialStore.findById(segment.getMaterialId()).orElse(null);
            if (material == null || MaterialSourcePolicy.allows(material, params)) continue;
            Map<String, Object> row = materialRow(material);
            row.put("index", segment.getIndex());
            row.put("slot", segment.getSlot());
            row.put("sourceStart", segment.getSourceStart());
            row.put("duration", segment.getDuration());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> materialRow(Material material) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", material.getId()); row.put("name", safeName(material)); row.put("fileType", material.getFileType());
        row.put("role", material.getRole()); row.put("durationSec", material.getDurationSec());
        row.put("source", material.getSource());
        row.put("sourceUrl", material.getSourceUrl());
        row.put("previewUrl", material.getPreviewUrl()); row.put("thumbnailUrl", material.getThumbnailUrl());
        return row;
    }

    private Map<String, Object> audioRow(Material material, Double durationSec) {
        Map<String, Object> row = materialRow(material);
        row.put("durationSec", durationSec);
        return row;
    }

    private Material readableVoice(Long id, MixParams params) {
        Material material = materialStore.findById(id).orElseThrow(() -> new IllegalArgumentException("口播音频不存在"));
        if (material.getFileType() != Material.FileType.audio || material.getStatus() == Material.Status.failed || material.getFilePath() == null) {
            throw new IllegalArgumentException("所选口播音频不可读");
        }
        if (!MaterialSourcePolicy.allows(material, params)) {
            throw new IllegalArgumentException("当前为仅本地素材模式，不能选择公开来源口播音频");
        }
        FfmpegTool.MediaInfo info = ffmpeg.probe(material.getFilePath());
        if (info == null || !info.isHasAudio() || info.getAudioDuration() <= 0) throw new IllegalArgumentException("所选口播音频无法解码");
        return material;
    }

    static List<Map<String, Object>> usedMaterials(MixPlanner.Plan plan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        double timeline = 0;
        for (MixPlanner.Segment segment : plan.getSegments()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", segment.getMaterialId()); row.put("name", segment.getMaterialName());
            row.put("slot", segment.getSlot()); row.put("kind", segment.getKind()); row.put("start", timeline);
            row.put("duration", segment.getDuration()); rows.add(row); timeline += segment.getDuration();
        }
        return rows;
    }

    private String write(Object value) { try { return om.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("无法保存编辑快照", e); } }
    private String validSlot(String slot) { return List.of("hook", "body", "celebrity", "product", "endcard", "intro").contains(slot) ? slot : "body"; }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private String safeName(Material material) { return material.getName() == null || material.getName().isBlank() ? "未命名素材" : material.getName(); }
    private String concise(Exception e) { String message = e.getMessage(); return e.getClass().getSimpleName() + (message == null ? "" : ": " + message); }
    private String limit(String value, int max) { return value == null ? null : value.substring(0, Math.min(max, value.length())); }
}
