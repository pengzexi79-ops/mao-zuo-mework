package com.douyin.mixcut.web;

import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.SkillDef;
import com.douyin.mixcut.domain.SkillType;
import com.douyin.mixcut.domain.Workflow;
import com.douyin.mixcut.domain.UseCase;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.repository.Repositories.SkillDefRepo;
import com.douyin.mixcut.repository.Repositories.WorkflowRepo;
import com.douyin.mixcut.service.BootstrapService;
import com.douyin.mixcut.service.MixPlanner;
import com.douyin.mixcut.service.PreflightService;
import com.douyin.mixcut.service.AudioContractService;
import com.douyin.mixcut.service.SkillEngine;
import com.douyin.mixcut.external.ProcessRegistry;
import com.douyin.mixcut.service.RenderAdmissionService;
import com.douyin.mixcut.dto.EffectiveRenderConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

/** 工作流、受约束自定义 Skill 管理，以及干跑预览。 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorkflowController {
    private static final Pattern SKILL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,79}");
    private static final Set<String> BUILTIN_NAMES = SkillEngine.BUILTIN.stream()
            .map(item -> String.valueOf(item.get("name"))).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final WorkflowRepo workflowRepo;
    private final SkillDefRepo skillRepo;
    private final ProjectRepo projectRepo;
    private final SkillEngine engine;
    private final PreflightService preflightService;
    private final AudioContractService audioContractService;
    private final RenderAdmissionService renderAdmissionService;
    private final BootstrapService bootstrapService;
    private final com.douyin.mixcut.service.AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/workflows")
    public R<List<Workflow>> list() { return R.ok(workflowRepo.findAllByOrderByIdAsc()); }

    @GetMapping("/workflows/{id}")
    public R<Workflow> get(@PathVariable Long id) { return workflowRepo.findById(id).map(R::ok).orElseGet(() -> R.fail("工作流不存在")); }

    @PostMapping("/workflows")
    public R<Workflow> create(@RequestBody Workflow workflow) {
        try {
            if (workflow == null || workflow.getName() == null || workflow.getName().isBlank()) return R.fail("名称不能为空");
            String definition = workflow.getDef() == null || workflow.getDef().isBlank() ? engine.defaultWorkflowDef() : workflow.getDef();
            workflow.setName(workflow.getName().trim());
            workflow.setDef(engine.validateWorkflowDefinition(definition));
            workflow.setId(null); workflow.setIsBuiltin(false);
            return R.ok(workflowRepo.save(workflow));
        } catch (IllegalArgumentException e) {
            return R.fail(safeValidationMessage(e));
        }
    }

    @PutMapping("/workflows/{id}")
    public R<Workflow> update(@PathVariable Long id, @RequestBody Workflow input) {
        Workflow workflow = workflowRepo.findById(id).orElse(null);
        if (workflow == null) return R.fail("工作流不存在");
        try {
            if (input == null) return R.fail("工作流请求不能为空");
            if (input.getName() != null) {
                if (input.getName().isBlank()) return R.fail("名称不能为空");
                workflow.setName(input.getName().trim());
            }
            if (input.getDescription() != null) workflow.setDescription(input.getDescription());
            if (input.getDef() != null && !input.getDef().isBlank()) workflow.setDef(engine.validateWorkflowDefinition(input.getDef()));
            if (input.getVersion() != null) workflow.setVersion(input.getVersion());
            return R.ok(workflowRepo.save(workflow));
        } catch (IllegalArgumentException e) {
            return R.fail(safeValidationMessage(e));
        }
    }

    @GetMapping("/workflows/{id}/export")
    public R<Map<String, Object>> exportWorkflow(@PathVariable Long id) {
        Workflow workflow = workflowRepo.findById(id).orElse(null);
        if (workflow == null) return R.fail("工作流不存在");
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("format", "mixcut-workflow");
        pack.put("schemaVersion", 1);
        pack.put("name", workflow.getName());
        pack.put("version", workflow.getVersion());
        pack.put("description", workflow.getDescription());
        pack.put("definition", parseDefinition(workflow.getDef()));
        pack.put("source", "喵作 · Mework");
        return R.ok(pack);
    }

    @Data
    public static class ImportWorkflowReq { private String pack; }

    @PostMapping("/workflows/import")
    public R<Workflow> importWorkflow(@RequestBody ImportWorkflowReq req) {
        try {
            JsonNode pack = readPack(req == null ? null : req.getPack(), "mixcut-workflow");
            JsonNode definition = pack.path("definition");
            if (!definition.isObject() || !definition.path("steps").isArray()) return R.fail("工作流包缺少有效 definition.steps");
            Workflow workflow = new Workflow();
            workflow.setName(requiredPackText(pack, "name", 100));
            workflow.setVersion(optionalPackText(pack, "version", 40, "1.0"));
            workflow.setDescription(optionalPackText(pack, "description", 500, "从导入包创建"));
            workflow.setDef(engine.validateWorkflowDefinition(objectMapper.writeValueAsString(definition)));
            workflow.setIsBuiltin(false);
            return R.ok(workflowRepo.save(workflow));
        } catch (IllegalArgumentException e) { return R.fail(safeValidationMessage(e));
        } catch (Exception e) { return R.fail("工作流导入失败，请检查 JSON 包格式"); }
    }

    @PostMapping("/workflows/{id}/duplicate")
    public R<Workflow> duplicate(@PathVariable Long id) {
        Workflow source = workflowRepo.findById(id).orElse(null);
        if (source == null) return R.fail("工作流不存在");
        try {
            Workflow workflow = new Workflow();
            workflow.setName(source.getName() + " 副本"); workflow.setDescription(source.getDescription());
            workflow.setDef(engine.validateWorkflowDefinition(source.getDef()));
            workflow.setVersion(source.getVersion()); workflow.setIsBuiltin(false);
            return R.ok(workflowRepo.save(workflow));
        } catch (IllegalArgumentException e) {
            return R.fail("源工作流无法复制：" + safeValidationMessage(e));
        }
    }

    @DeleteMapping("/workflows/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Workflow workflow = workflowRepo.findById(id).orElse(null);
        if (workflow == null) return R.ok();
        if (Boolean.TRUE.equals(workflow.getIsBuiltin())) return R.fail("内置工作流不可删除，可先复制再改");
        workflowRepo.deleteById(id); return R.ok();
    }

    // ---------------- Skill ----------------

    @GetMapping("/skills")
    public R<List<SkillDef>> skills() { return R.ok(skillRepo.findAll()); }

    @GetMapping("/skills/builtin")
    public R<List<Map<String, Object>>> builtin() { return R.ok(SkillEngine.BUILTIN); }

    @Data
    public static class ValidateSkillReq { private String def; private String type; }

    /** 编辑器即时校验入口：只返回明确的业务消息，不向浏览器泄露后端异常。 */
    @PostMapping("/skills/validate")
    public R<Map<String, Object>> validateSkill(@RequestBody ValidateSkillReq req) {
        try {
            String normalized = engine.validateCustomSkill(req == null ? null : req.getDef(), req == null ? null : req.getType());
            return R.ok(Map.of("valid", true, "message", "技能规则校验通过", "normalizedDef", normalized));
        } catch (IllegalArgumentException e) {
            return R.ok(Map.of("valid", false, "message", safeValidationMessage(e)));
        } catch (Exception e) {
            log.warn("Skill DSL validation failed without details", e);
            return R.ok(Map.of("valid", false, "message", "技能规则校验失败，请检查定义格式"));
        }
    }

    @PostMapping("/skills")
    public R<SkillDef> createSkill(@RequestBody SkillDef skill) {
        try {
            validateSkillForSave(skill, null);
            skill.setId(null);
            skill.setName(skill.getName().trim());
            skill.setDescription(skill.getDescription().trim());
            skill.setDef(engine.validateCustomSkill(skill.getDef(), skill.getType()));
            skill.setEnabled(!Boolean.FALSE.equals(skill.getEnabled()));
            return R.ok(skillRepo.save(skill));
        } catch (IllegalArgumentException e) {
            return R.fail(safeValidationMessage(e));
        }
    }

    @PutMapping("/skills/{id}")
    public R<SkillDef> updateSkill(@PathVariable Long id, @RequestBody SkillDef input) {
        SkillDef skill = skillRepo.findById(id).orElse(null);
        if (skill == null) return R.fail("技能不存在");
        try {
            // 名称不可在更新中改写，防止绕过唯一名和内置名冲突检查。
            String candidateType = input.getType() == null ? skill.getType() : input.getType();
            String candidateDescription = input.getDescription() == null ? skill.getDescription() : input.getDescription();
            String candidateDef = input.getDef() == null ? skill.getDef() : input.getDef();
            // 保留系统 builtin Skill 的既有管理行为；只有 script/ai 自定义 Skill 走 DSL 校验。
            if (SkillType.builtin.name().equals(skill.getType()) && SkillType.builtin.name().equals(candidateType)) {
                if (candidateDescription != null) skill.setDescription(candidateDescription.trim());
                if (input.getEnabled() != null) skill.setEnabled(input.getEnabled());
                return R.ok(skillRepo.save(skill));
            }
            SkillDef candidate = new SkillDef();
            candidate.setName(skill.getName()); candidate.setType(candidateType);
            candidate.setDescription(candidateDescription); candidate.setDef(candidateDef);
            validateSkillForSave(candidate, id);
            skill.setType(candidateType.trim());
            skill.setDescription(candidateDescription.trim());
            skill.setDef(engine.validateCustomSkill(candidateDef, candidateType));
            if (input.getEnabled() != null) skill.setEnabled(input.getEnabled());
            return R.ok(skillRepo.save(skill));
        } catch (IllegalArgumentException e) {
            return R.fail(safeValidationMessage(e));
        }
    }

    @GetMapping("/skills/{id}/export")
    public R<Map<String, Object>> exportSkill(@PathVariable Long id) {
        SkillDef skill = skillRepo.findById(id).orElse(null);
        if (skill == null) return R.fail("技能不存在");
        if (SkillType.builtin.name().equals(skill.getType())) return R.fail("内置技能不可导出为自定义 Skill 包");
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("format", "mixcut-skill");
        pack.put("schemaVersion", 1);
        pack.put("name", skill.getName());
        pack.put("type", skill.getType());
        pack.put("description", skill.getDescription());
        pack.put("definition", parseDefinition(skill.getDef()));
        pack.put("source", "喵作 · Mework");
        return R.ok(pack);
    }

    @Data
    public static class ImportSkillReq { private String pack; }

    @PostMapping("/skills/import")
    public R<SkillDef> importSkill(@RequestBody ImportSkillReq req) {
        try {
            JsonNode pack = readPack(req == null ? null : req.getPack(), "mixcut-skill");
            SkillDef skill = new SkillDef();
            skill.setName(requiredPackText(pack, "name", 80));
            skill.setType(optionalPackText(pack, "type", 20, SkillType.script.name()));
            skill.setDescription(requiredPackText(pack, "description", 1000));
            JsonNode definition = pack.path("definition");
            skill.setDef(objectMapper.writeValueAsString(definition));
            skill.setEnabled(true);
            validateSkillForSave(skill, null);
            skill.setDef(engine.validateCustomSkill(skill.getDef(), skill.getType()));
            return R.ok(skillRepo.save(skill));
        } catch (IllegalArgumentException e) { return R.fail(safeValidationMessage(e));
        } catch (Exception e) { return R.fail("Skill 导入失败，请检查 JSON 包格式"); }
    }

    @DeleteMapping("/skills/{id}")
    public R<Void> deleteSkill(@PathVariable Long id) { skillRepo.deleteById(id); return R.ok(); }

    private void validateSkillForSave(SkillDef skill, Long updatingId) {
        if (skill == null) throw new IllegalArgumentException("技能请求不能为空");
        String name = trim(skill.getName());
        if (!SKILL_NAME.matcher(name).matches()) throw new IllegalArgumentException("技能名称仅允许字母开头的字母、数字、_ 或 -，最长 80 位");
        if (BUILTIN_NAMES.contains(name)) throw new IllegalArgumentException("技能名称不能覆盖内置 Skill: " + name);
        SkillDef duplicate = skillRepo.findByName(name).orElse(null);
        if (duplicate != null && !Objects.equals(duplicate.getId(), updatingId)) throw new IllegalArgumentException("同名 Skill 已存在");
        if (!SkillType.script.name().equals(skill.getType()) && !SkillType.ai.name().equals(skill.getType())) {
            throw new IllegalArgumentException("Skill 类型只允许 script 或 ai");
        }
        if (trim(skill.getDescription()).isEmpty() || trim(skill.getDescription()).length() > 1000) {
            throw new IllegalArgumentException("Skill 描述不能为空且不能超过 1000 字");
        }
        engine.validateCustomSkill(skill.getDef(), skill.getType());
    }

    private JsonNode readPack(String raw, String expectedFormat) {
        try {
            JsonNode pack = objectMapper.readTree(raw == null ? "" : raw);
            if (!pack.isObject()) throw new IllegalArgumentException("导入包必须是 JSON 对象");
            if (!expectedFormat.equals(pack.path("format").asText())) throw new IllegalArgumentException("导入包类型不匹配");
            if (pack.path("schemaVersion").asInt(0) != 1) throw new IllegalArgumentException("不支持的导入包版本");
            return pack;
        } catch (IllegalArgumentException e) { throw e;
        } catch (Exception e) { throw new IllegalArgumentException("导入包不是有效 JSON"); }
    }

    private Object parseDefinition(String raw) {
        try { return objectMapper.readTree(raw); } catch (Exception e) { return Map.of("steps", List.of()); }
    }

    private String requiredPackText(JsonNode pack, String key, int max) {
        String value = optionalPackText(pack, key, max, "");
        if (value.isBlank()) throw new IllegalArgumentException("导入包缺少 " + key);
        return value;
    }

    private String optionalPackText(JsonNode pack, String key, int max, String fallback) {
        String value = trim(pack.path(key).asText(fallback));
        if (value.length() > max) throw new IllegalArgumentException(key + " 超出长度限制");
        return value.isBlank() ? fallback : value;
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }

    private int clamp(Integer value, int min, int max, int fallback) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(max, v));
    }

    private String normalizeRatio(String value) {
        String ratio = trim(value);
        return Set.of("9:16", "16:9", "1:1").contains(ratio) ? ratio : "9:16";
    }

    private Map<String, Object> buildComicFallback(String title, String world, String characters, String style, String ratio,
                                                   int chapters, int shotsPerChapter, int targetSec, String requirement) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("world", world);
        result.put("style", style.isBlank() ? "竖屏短剧漫画风，清晰人物表情，镜头节奏紧凑" : style);
        result.put("ratio", ratio);
        List<String> characterRows = trim(characters).isBlank()
                ? List.of("主角：目标用户视角，负责提出问题和做出选择", "对照角色：制造冲突或给出反应")
                : Arrays.stream(characters.split("[\\n,，；;]+"))
                .map(String::trim).filter(s -> !s.isBlank()).limit(8).toList();
        result.put("characters", characterRows);
        int totalShots = Math.max(1, Math.min(24, chapters * shotsPerChapter));
        int duration = Math.max(1, Math.min(20, Math.round((float) targetSec / totalShots)));
        List<Map<String, Object>> shots = new ArrayList<>();
        for (int i = 0; i < totalShots; i++) {
            int chapter = i / Math.max(1, shotsPerChapter) + 1;
            int index = i % Math.max(1, shotsPerChapter) + 1;
            String hook = i == 0 ? "前三秒强钩子，直接出现矛盾和结果期待" : index == shotsPerChapter ? "本章小反转或转场悬念" : "推进人物行动和情绪变化";
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("chapter", "第" + chapter + "章");
            shot.put("title", "镜头 " + (i + 1));
            shot.put("content", hook + "。" + (requirement.isBlank() ? "围绕项目卖点组织画面。" : requirement));
            shot.put("prompt", title + "，" + hook + "，" + result.get("style") + "，" + (world.isBlank() ? "真实可理解的短剧场景" : world));
            shot.put("duration", duration);
            shot.put("ratio", ratio);
            shot.put("kind", i % 3 == 0 ? "video" : "image");
            shots.add(shot);
        }
        result.put("shots", shots);
        return result;
    }

    private String safeValidationMessage(IllegalArgumentException e) {
        String message = e.getMessage();
        return message == null || message.length() > 300 ? "Skill 定义不合法" : message;
    }

    // ---------------- AI 自动编排 ----------------

    @Data
    public static class AiPlanReq { private Long projectId; private String requirement; private Boolean save = false; private String name; }

    @PostMapping("/workflows/ai-plan")
    public R<Map<String, Object>> aiPlan(@RequestBody AiPlanReq req) {
        Project project = req.getProjectId() == null ? null : projectRepo.findById(req.getProjectId()).orElse(null);
        String def = engine.aiPlan(project, req.getRequirement());
        Long savedId = null;
        if (Boolean.TRUE.equals(req.getSave())) {
            Workflow workflow = new Workflow();
            workflow.setName(req.getName() == null || req.getName().isBlank() ? "AI 编排 " + System.currentTimeMillis() % 100000 : req.getName());
            workflow.setDescription("由 AI 根据素材库现状自动编排" + (req.getRequirement() == null ? "" : "：" + req.getRequirement()));
            workflow.setDef(engine.validateWorkflowDefinition(def)); workflow.setVersion("1.0"); workflow.setIsBuiltin(false);
            savedId = workflowRepo.save(workflow).getId();
        }
        return R.ok(Map.of("def", def, "savedId", savedId == null ? -1 : savedId));
    }

    @Data
    public static class AiComicReq {
        private Long projectId;
        private String title;
        private String world;
        private String characters;
        private String style;
        private String ratio = "9:16";
        private Integer chapters = 4;
        private Integer shotsPerChapter = 4;
        private Integer targetSec = 60;
        private String requirement;
    }

    @PostMapping("/workflows/ai-comic")
    public R<Map<String, Object>> aiComic(@RequestBody AiComicReq req) {
        Project project = req != null && req.getProjectId() != null ? projectRepo.findById(req.getProjectId()).orElse(null) : null;
        String ratio = normalizeRatio(req == null ? null : req.getRatio());
        int chapters = clamp(req == null ? null : req.getChapters(), 1, 12, 4);
        int shotsPerChapter = clamp(req == null ? null : req.getShotsPerChapter(), 1, 8, 4);
        int targetSec = clamp(req == null ? null : req.getTargetSec(), 10, 600, 60);
        String baseTitle = trim(req == null ? null : req.getTitle());
        if (baseTitle.isBlank()) baseTitle = project == null ? "漫剧项目" : trim(project.getName());
        Map<String, Object> fallback = buildComicFallback(baseTitle, trim(req == null ? null : req.getWorld()), trim(req == null ? null : req.getCharacters()), trim(req == null ? null : req.getStyle()), ratio, chapters, shotsPerChapter, targetSec, trim(req == null ? null : req.getRequirement()));
        if (!aiService.ready()) {
            fallback.put("aiUsed", false);
            fallback.put("message", "AI 未配置，已使用本地漫剧分镜草稿");
            return R.ok(fallback);
        }
        String system = "你是漫剧分镜编排器。只输出 JSON 对象，不要 Markdown，不要解释，不要代码块。"
                + "必须返回 {\"title\":string,\"world\":string,\"style\":string,\"ratio\":\"9:16\"|\"16:9\"|\"1:1\",\"characters\":[string],\"shots\":[{\"chapter\":string,\"title\":string,\"content\":string,\"prompt\":string,\"duration\":number,\"ratio\":string,\"kind\":\"image\"|\"video\"}]}."
                + "shots 数量应在 4 到 24 之间，content 是可直接放进画布分镜节点的中文描述，prompt 是可用于生成图片或视频的视觉提示。"
                + "duration 取 1 到 20 的整数，ratio 只能是 9:16、16:9、1:1，kind 只能是 image 或 video。";
        StringBuilder user = new StringBuilder();
        user.append("基础信息：标题=").append(baseTitle).append("；画幅=").append(ratio)
                .append("；章节数=").append(chapters).append("；每章镜头数=").append(shotsPerChapter)
                .append("；总时长目标=").append(targetSec).append("秒");
        if (project != null) {
            user.append("；项目品牌=").append(trim(project.getBrand()))
                    .append("；品类=").append(trim(project.getCategory()))
                    .append("；产品=").append(trim(project.getProduct()))
                    .append("；卖点=").append(trim(project.getSellingPoints()))
                    .append("；受众=").append(trim(project.getAudience()));
        }
        if (!trim(req == null ? null : req.getWorld()).isBlank()) user.append("；世界观=").append(trim(req.getWorld()));
        if (!trim(req == null ? null : req.getCharacters()).isBlank()) user.append("；角色=").append(trim(req.getCharacters()));
        if (!trim(req == null ? null : req.getStyle()).isBlank()) user.append("；画风=").append(trim(req.getStyle()));
        if (!trim(req == null ? null : req.getRequirement()).isBlank()) user.append("；额外要求=").append(trim(req.getRequirement()));
        JsonNode response = aiService.askJson(UseCase.general, system, user.toString(), 0.45, 2200, project == null ? null : project.getRouteOverrides());
        if (response == null || !response.isObject() || !response.path("shots").isArray() || response.path("shots").isEmpty()) {
            fallback.put("aiUsed", false);
            fallback.put("message", "AI 返回格式无效，已使用本地漫剧分镜草稿");
            return R.ok(fallback);
        }
        Map<String, Object> result = objectMapper.convertValue(response, Map.class);
        result.putIfAbsent("title", baseTitle);
        result.putIfAbsent("world", trim(req == null ? null : req.getWorld()));
        result.putIfAbsent("style", trim(req == null ? null : req.getStyle()));
        result.putIfAbsent("ratio", ratio);
        result.putIfAbsent("characters", List.of());
        result.put("aiUsed", true);
        result.put("message", "已生成漫剧分镜草稿");
        return R.ok(result);
    }

    // ---------------- 固定顺序 AI 建议 ----------------

    @Data
    public static class FixedOrderSuggestionReq { private Long projectId; private String requirement; }

    @PostMapping("/fixed-order/suggest")
    public R<Map<String, Object>> fixedOrderSuggestion(@RequestBody FixedOrderSuggestionReq req) {
        Project project = req == null || req.getProjectId() == null ? null : projectRepo.findById(req.getProjectId()).orElse(null);
        List<Map<String, Object>> fallback = defaultFixedOrderSteps();
        if (!aiService.ready()) return R.ok(Map.of("stages", fallback, "aiUsed", false, "message", "AI 未配置，已使用可编辑的基础顺序建议"));
        String requirement = trim(req == null ? null : req.getRequirement());
        if (requirement.length() > 500) return R.fail("顺序要求不能超过 500 字");
        String projectText = project == null ? "未选择项目" : "项目：" + trim(project.getName()) + "；品类：" + trim(project.getCategory())
                + "；产品：" + trim(project.getProduct()) + "；卖点：" + trim(project.getSellingPoints()) + "；受众：" + trim(project.getAudience());
        String system = "你是短视频产片顺序助手。只返回 JSON 数组，不要 Markdown、解释、URL、命令、文件路径或下载信息。"
                + "数组包含 3 到 12 个对象，每个对象只可有 name、targetSec、folderKeywords、shortagePolicy。"
                + "name 为 2-80 个普通文字；targetSec 为 1-300；folderKeywords 是至多 5 个普通关键词；shortagePolicy 只能是 block 或 fallback。";
        try {
            var answer = aiService.ask(UseCase.general, system, projectText + "\n用户要求：" + requirement, 0.2, 700,
                    project == null ? null : project.getRouteOverrides());
            if (!answer.ok()) return R.ok(Map.of("stages", fallback, "aiUsed", false, "message", "AI 暂不可用，已使用基础顺序建议"));
            List<Map<String, Object>> stages = sanitizeFixedOrderStages(answer.text());
            if (stages.isEmpty()) return R.ok(Map.of("stages", fallback, "aiUsed", false, "message", "AI 返回格式无效，已使用基础顺序建议"));
            return R.ok(Map.of("stages", stages, "aiUsed", true, "message", "已生成可继续编辑的顺序建议"));
        } catch (Exception e) {
            log.info("fixed-order suggestion fallback: {}", e.getClass().getSimpleName());
            return R.ok(Map.of("stages", fallback, "aiUsed", false, "message", "AI 建议暂不可用，已使用基础顺序建议"));
        }
    }

    private List<Map<String, Object>> sanitizeFixedOrderStages(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw == null ? "" : raw.trim());
            if (!root.isArray() || root.size() < 3 || root.size() > 12) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            int order = 1;
            for (JsonNode node : root) {
                if (!node.isObject()) return List.of();
                String name = optionalPackText(node, "name", 80, "");
                if (name.length() < 2) return List.of();
                int targetSec = Math.max(1, Math.min(300, node.path("targetSec").asInt(6)));
                String shortagePolicy = "fallback".equalsIgnoreCase(node.path("shortagePolicy").asText()) ? "fallback" : "block";
                List<String> keywords = new ArrayList<>();
                if (node.path("folderKeywords").isArray()) for (JsonNode keyword : node.path("folderKeywords")) {
                    String value = trim(keyword.asText());
                    if (!value.isEmpty() && value.length() <= 40 && value.matches("[\\p{IsHan}A-Za-z0-9 _-]+")) keywords.add(value);
                    if (keywords.size() == 5) break;
                }
                Map<String, Object> stage = new LinkedHashMap<>();
                stage.put("order", order++); stage.put("name", name); stage.put("targetSec", targetSec);
                stage.put("folderKeywords", keywords); stage.put("shortagePolicy", shortagePolicy);
                result.add(stage);
            }
            return result;
        } catch (Exception ignored) { return List.of(); }
    }

    private List<Map<String, Object>> defaultFixedOrderSteps() {
        return List.of("开头钩子", "痛点场景", "真实反应").stream().map(name -> {
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("order", name.equals("开头钩子") ? 1 : name.equals("痛点场景") ? 2 : 3);
            stage.put("name", name); stage.put("targetSec", 6); stage.put("folderKeywords", List.of()); stage.put("shortagePolicy", "block");
            return stage;
        }).toList();
    }

    // ---------------- 干跑预览 ----------------

    @Data
    public static class DryRunReq { private Long workflowId; private Long projectId; private MixParams params; private Integer variant = 0; private String preparationId; }

    @Data
    public static class DryRunResult {
        private MixPlanner.Plan plan;
        private com.douyin.mixcut.dto.PreflightResult preflight;

        public DryRunResult(MixPlanner.Plan plan, com.douyin.mixcut.dto.PreflightResult preflight) {
            this.plan = plan;
            this.preflight = preflight;
        }
    }

    @PostMapping("/workflows/dry-run")
    public R<DryRunResult> dryRun(@RequestBody DryRunReq req) {
        String def = null;
        if (req.getWorkflowId() != null) {
            Workflow workflow = workflowRepo.findById(req.getWorkflowId()).orElse(null);
            if (workflow != null) def = workflow.getDef();
        }
        if (def == null) def = engine.defaultWorkflowDef();
        Project project = req.getProjectId() == null ? null : projectRepo.findById(req.getProjectId()).orElse(null);
        MixParams params = req.getParams() == null ? new MixParams() : req.getParams();
        EffectiveRenderConfig config = renderAdmissionService.resolve(req.getWorkflowId(), req.getProjectId(), params,
                req.getVariant(), req.getPreparationId());
        MixParams normalized = config.getParams();
        def = config.getWorkflowDef();
        MixPlanner.Plan plan = engine.run(def, config.getProject(), config.getParams(), config.getVariant(), null, null);
        Map<String, Object> environment = bootstrapService.env();
        boolean ffmpegReady = Boolean.TRUE.equals(environment.get("ffmpeg"));
        boolean ffprobeReady = Boolean.TRUE.equals(environment.get("ffprobe"));
        var preflight = preflightService.evaluate(plan, normalized, ffmpegReady, ffprobeReady);
        preflightService.attachAudioContract(preflight, plan, normalized, audioContractService,
                ProcessRegistry.CancellationContext.none());
        renderAdmissionService.seal(config.getAdmission(), preflight.getStatus());
        preflightService.attachAdmission(preflight, config.getAdmission());
        return R.ok(new DryRunResult(plan, preflight));
    }
}
