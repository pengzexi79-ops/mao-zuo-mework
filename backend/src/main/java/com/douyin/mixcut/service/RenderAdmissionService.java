package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.Workflow;
import com.douyin.mixcut.dto.AdmissionSnapshot;
import com.douyin.mixcut.dto.EffectiveRenderConfig;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.dto.PreflightResult;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.repository.Repositories.WorkflowRepo;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.external.FfmpegTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Resolves and fingerprints the exact configuration admitted for rendering. */
@Service
public class RenderAdmissionService {
    private final ProjectRepo projectRepo;
    private final WorkflowRepo workflowRepo;
    private final ObjectMapper om;
    private final RenderConfigResolver configResolver;
    private final SkillEngine skillEngine;
    private final PreflightService preflightService;
    private final FfmpegTool ffmpeg;
    private final AudioContractService audioContractService;
    private final MaterialStore materialStore;

    public RenderAdmissionService(ProjectRepo projectRepo, WorkflowRepo workflowRepo, ObjectMapper om,
                                  RenderConfigResolver configResolver, SkillEngine skillEngine,
                                  PreflightService preflightService, FfmpegTool ffmpeg,
                                  AudioContractService audioContractService) {
        this(projectRepo, workflowRepo, om, configResolver, skillEngine, preflightService, ffmpeg,
                audioContractService, null);
    }

    @Autowired
    public RenderAdmissionService(ProjectRepo projectRepo, WorkflowRepo workflowRepo, ObjectMapper om,
                                  RenderConfigResolver configResolver, SkillEngine skillEngine,
                                  PreflightService preflightService, FfmpegTool ffmpeg,
                                  AudioContractService audioContractService, MaterialStore materialStore) {
        this.projectRepo = projectRepo;
        this.workflowRepo = workflowRepo;
        this.om = om;
        this.configResolver = configResolver;
        this.skillEngine = skillEngine;
        this.preflightService = preflightService;
        this.ffmpeg = ffmpeg;
        this.audioContractService = audioContractService;
        this.materialStore = materialStore;
    }

    public EffectiveRenderConfig resolve(Long workflowId, Long projectId, MixParams submitted) {
        return resolve(workflowId, projectId, submitted, 0, null);
    }

    public EffectiveRenderConfig resolve(Long workflowId, Long projectId, MixParams submitted,
                                         Integer variant, String preparationId) {
        return resolveJson(workflowId, projectId, write(submitted == null ? new MixParams() : submitted), variant, preparationId);
    }

    public EffectiveRenderConfig resolveJson(Long workflowId, Long projectId, String submittedJson) {
        return resolveJson(workflowId, projectId, submittedJson, 0, null);
    }

    public EffectiveRenderConfig resolveJson(Long workflowId, Long projectId, String submittedJson,
                                             Integer variant, String preparationId) {
        Project project = projectId == null ? null : projectRepo.findById(projectId).orElse(null);
        Workflow workflow = workflowId == null ? null : workflowRepo.findById(workflowId).orElse(null);
        MixParams effective = configResolver.mergeProjectDefaults(submittedJson, project).normalized();
        if (effective.getSeed() == null) effective.setSeed(stableSeed(workflowId, projectId, write(effective)));
        int effectiveVariant = variant == null ? 0 : Math.max(0, variant);
        String normalizedPreparationId = blankToNull(preparationId);
        String workflowDef = workflow == null ? skillEngine.defaultWorkflowDef() : workflow.getDef();
        Map<String, Object> workflowFacts = new LinkedHashMap<>();
        workflowFacts.put("id", workflow == null ? null : workflow.getId());
        workflowFacts.put("name", workflow == null ? null : workflow.getName());
        workflowFacts.put("version", workflow == null ? null : workflow.getVersion());
        workflowFacts.put("def", parse(workflowDef));
        String workflowCanonical = canonical(workflowFacts);
        String scopeCanonical = canonical(materialScope(effective));
        Map<String, Object> configFacts = new LinkedHashMap<>();
        configFacts.put("params", parse(write(effective)));
        configFacts.put("workflow", parse(workflowCanonical));
        configFacts.put("materialScope", parse(scopeCanonical));
        configFacts.put("variant", effectiveVariant);
        configFacts.put("preparationId", normalizedPreparationId);
        String configCanonical = canonical(configFacts);
        EffectiveRenderConfig result = new EffectiveRenderConfig();
        result.setProject(project);
        result.setWorkflow(workflow);
        result.setWorkflowDef(workflowDef);
        result.setParams(effective);
        result.setWorkflowHash(sha256(workflowCanonical));
        result.setMaterialScopeHash(sha256(scopeCanonical));
        result.setConfigHash(sha256(configCanonical));
        result.setVariant(effectiveVariant);
        result.setPreparationId(normalizedPreparationId);
        result.setAdmission(snapshot(result));
        return result;
    }

    public AdmissionSnapshot seal(AdmissionSnapshot snapshot, String status) {
        snapshot.setStatus(status == null || status.isBlank() ? "ready" : status);
        snapshot.setStatusSignature(signature(snapshot));
        return snapshot;
    }

    public AdmissionSnapshot snapshot(EffectiveRenderConfig config) {
        return snapshot(config, LocalDateTime.now());
    }

    private AdmissionSnapshot snapshot(EffectiveRenderConfig config, LocalDateTime checkedAt) {
        AdmissionSnapshot snapshot = new AdmissionSnapshot();
        snapshot.setConfigHash(config.getConfigHash());
        snapshot.setWorkflowHash(config.getWorkflowHash());
        snapshot.setMaterialScopeHash(config.getMaterialScopeHash());
        snapshot.setCheckedAt(checkedAt);
        snapshot.setExpiresAt(checkedAt.plusMinutes(15));
        snapshot.setPreparationId(config.getPreparationId());
        snapshot.setVariant(config.getVariant());
        snapshot.setStatus("ready");
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("workflowId", config.getWorkflow() == null ? null : config.getWorkflow().getId());
        runtime.put("projectId", config.getProject() == null ? null : config.getProject().getId());
        runtime.put("variant", config.getVariant());
        runtime.put("runtimeAvailable", true);
        snapshot.setRuntimeSnapshot(runtime);
        snapshot.setStatusSignature(signature(snapshot));
        return snapshot;
    }

    public void verify(AdmissionSnapshot supplied, EffectiveRenderConfig actual) {
        if (supplied == null) throw new IllegalArgumentException("请先执行干跑，再提交出片任务");
        if (supplied.getStatusSignature() == null || !Objects.equals(supplied.getStatusSignature(), signature(supplied)))
            throw new IllegalArgumentException("出片准入状态签名无效，请重新干跑");
        if (supplied.getExpiresAt() == null || supplied.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new IllegalArgumentException("出片准入快照已过期，请重新干跑");
        if (!Objects.equals(supplied.getConfigHash(), actual.getConfigHash())
                || !Objects.equals(supplied.getWorkflowHash(), actual.getWorkflowHash())
                || !Objects.equals(supplied.getMaterialScopeHash(), actual.getMaterialScopeHash())
                || !Objects.equals(supplied.getVariant(), actual.getVariant())
                || !Objects.equals(blankToNull(supplied.getPreparationId()), actual.getPreparationId()))
            throw new IllegalArgumentException("出片准入快照与当前工作流、项目、变体、准备任务或素材范围不一致，请重新干跑");

        MixPlanner.Plan plan = skillEngine.run(actual.getWorkflowDef(), actual.getProject(), actual.getParams(),
                actual.getVariant() == null ? 0 : actual.getVariant(), null, null);
        PreflightResult preflight = preflightService.evaluate(plan, actual.getParams(),
                ffmpeg.ffmpegAvailable(), ffmpeg.ffprobeAvailable());
        preflightService.attachAudioContract(preflight, plan, actual.getParams(),
                audioContractService, com.douyin.mixcut.external.ProcessRegistry.CancellationContext.none());
        String expected = preflight.getStatus();
        if (PreflightResult.BLOCKED.equals(expected) || PreflightResult.NEEDS_USER_ACTION.equals(expected)
                || !Objects.equals(expected, supplied.getStatus())) {
            throw new IllegalArgumentException("当前出片准入状态不可提交，请重新干跑: " + expected);
        }
    }

    private Map<String, Object> materialScope(MixParams p) {
        Map<String, Object> scope = new LinkedHashMap<>();
        List<Long> materialIds = sorted(p.getMaterialIds());
        List<Long> folderIds = materialFolderIds(p);
        scope.put("materialIds", materialIds);
        scope.put("folderIds", folderIds);
        scope.put("folderReadSteps", p.getFolderReadSteps());
        if (materialStore != null) scope.put("materialFacts", materialFacts(materialIds, folderIds));
        return scope;
    }

    private List<Map<String, Object>> materialFacts(List<Long> materialIds, List<Long> folderIds) {
        Set<Long> selected = new HashSet<>(materialIds);
        Set<Long> folders = new HashSet<>(folderIds);
        List<Material> materials = materialStore.findAll();
        List<Map<String, Object>> facts = materials.stream()
                .filter(material -> material != null && material.getId() != null
                        && (selected.contains(material.getId()) || folders.contains(material.getFolderId())))
                .sorted(Comparator.comparing(Material::getId))
                .map(this::materialFact)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Set<Long> found = materials.stream().filter(Objects::nonNull).map(Material::getId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        selected.stream().filter(id -> !found.contains(id)).sorted().forEach(id -> {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("id", id);
            missing.put("missing", "missing");
            facts.add(missing);
        });
        facts.sort(Comparator.comparing(fact -> ((Number) fact.get("id")).longValue()));
        return facts;
    }

    private Map<String, Object> materialFact(Material material) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("id", material.getId());
        fact.put("filePathHash", material.getFilePath() == null ? null : sha256(material.getFilePath()));
        fact.put("fileSize", null);
        fact.put("lastModified", null);
        PathFact pathFact = fileFact(material.getFilePath());
        if (pathFact.readable()) {
            fact.put("fileSize", pathFact.size());
            fact.put("lastModified", pathFact.lastModified());
        } else {
            fact.put("missing", "missing");
        }
        fact.put("durationSec", material.getDurationSec());
        fact.put("status", material.getStatus());
        fact.put("role", material.getRole());
        return fact;
    }

    private PathFact fileFact(String filePath) {
        if (filePath == null || filePath.isBlank()) return new PathFact(false, null, null);
        try {
            Path path = Path.of(filePath);
            if (!Files.isReadable(path)) return new PathFact(false, null, null);
            return new PathFact(true, Files.size(path), Files.getLastModifiedTime(path).toMillis());
        } catch (Exception ignored) {
            return new PathFact(false, null, null);
        }
    }

    private record PathFact(boolean readable, Long size, Long lastModified) {}

    private List<Long> materialFolderIds(MixParams p) {
        Set<Long> ids = new HashSet<>(sorted(p.getFolderIds()));
        if (p.getFolderReadSteps() != null) {
            for (MixParams.FolderReadStep step : p.getFolderReadSteps()) {
                if (step == null) continue;
                if (step.getFolderId() != null) ids.add(step.getFolderId());
                if (step.getFallbackFolderId() != null) ids.add(step.getFallbackFolderId());
            }
        }
        return ids.stream().filter(Objects::nonNull).sorted().toList();
    }

    private List<Long> sorted(List<Long> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).sorted().toList();
    }

    public String canonical(Object value) {
        try { return om.writeValueAsString(sort(om.valueToTree(value))); }
        catch (Exception e) { throw new IllegalArgumentException("无法规范化出片配置", e); }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            var out = om.createObjectNode();
            List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            for (String name : names) out.set(name, sort(node.get(name)));
            return out;
        }
        if (node.isArray()) { var out = om.createArrayNode(); node.forEach(item -> out.add(sort(item))); return out; }
        return node;
    }

    private JsonNode parse(String raw) { try { return om.readTree(raw == null ? "null" : raw); } catch (Exception e) { return om.nullNode(); } }
    private String write(Object value) { try { return om.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("无法序列化出片配置", e); } }
    private String signature(AdmissionSnapshot snapshot) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("status", snapshot.getStatus());
        facts.put("configHash", snapshot.getConfigHash());
        facts.put("workflowHash", snapshot.getWorkflowHash());
        facts.put("materialScopeHash", snapshot.getMaterialScopeHash());
        facts.put("variant", snapshot.getVariant() == null ? 0 : snapshot.getVariant());
        facts.put("preparationId", blankToNull(snapshot.getPreparationId()));
        facts.put("checkedAt", snapshot.getCheckedAt() == null ? null : snapshot.getCheckedAt().toString());
        facts.put("expiresAt", snapshot.getExpiresAt() == null ? null : snapshot.getExpiresAt().toString());
        return sha256(canonical(facts));
    }

    private long stableSeed(Long workflowId, Long projectId, String effectiveParams) {
        String value = String.valueOf(workflowId) + ":" + String.valueOf(projectId) + ":" + effectiveParams;
        byte[] digest;
        try { digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 不可用", e); }
        long result = 0;
        for (int i = 0; i < 8; i++) result = (result << 8) | (digest[i] & 0xffL);
        return result == Long.MIN_VALUE ? 1L : Math.abs(result);
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String sha256(String value) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 不可用", e); }
    }
}
