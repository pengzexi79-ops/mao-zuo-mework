package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.*;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import com.douyin.mixcut.repository.Repositories.SkillDefRepo;
import com.douyin.mixcut.security.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 受约束的工作流 Skill 引擎。
 *
 * 用户自定义 script/ai Skill 不是可执行脚本：其 def 只能是本类验证过的 JSON DSL。
 * DSL 只允许操作素材池和 MixParams，绝不接受命令、模板、网络请求或下载动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillEngine {
    static final int MAX_DSL_STEPS = 30;
    private static final int MAX_TEXT = 1000;
    private static final int MAX_KEYWORD = 120;
    private static final int MAX_SKILL_NAME = 80;
    private static final Set<String> DSL_OPS = Set.of(
            "select_materials", "set_params", "set_hook", "set_script", "pick_audio", "note");
    private static final Set<String> MIX_PARAM_FIELDS = Set.of(
            "minSec", "maxSec", "dense", "targetSec", "sliceSec", "sliceJitter", "explodeLongClips",
            "maxSlicesPerMaterial", "hookSec", "celebrityRatio", "productSlots", "productSec", "endcard", "endcardSec", "requireDedicatedEndcard",
            "width", "height", "fps", "bgmVolume", "bgmMaterialId", "voiceMaterialId", "hookAudioMaterialId", "autoMatchAudio", "hookAudioVolume", "audioMode", "originalAudioVolume", "ttsVoice", "autoSubtitles", "burnAiVoiceCaptions", "cleanSourceSubtitles", "sourceSubtitleCleanMode", "aiHook",
            "hookText", "burnHookText", "hookFontSize", "hookFontColor", "materialIds", "folderIds", "projectRelevantOnly", "autoUseCrawledMaterials", "materialSourceMode",
            "dedupStrictness", "seed", "namePrefix");
    private static final Pattern FORBIDDEN_FIELD = Pattern.compile(
            "(?i).*(command|cmd|shell|bash|powershell|exec|process|ffmpeg|template|url|uri|http|download|fetch|script|code|filePath).*" );

    private final MaterialStore materialRepo;
    private final MaterialFolderRepo folderRepo;
    private final SkillDefRepo skillRepo;
    private final MaterialService materialService;
    private final CopyService copyService;
    private final AiService aiService;
    private final MixPlanner planner;
    private final CrawlerGateway crawler;
    private final ObjectMapper om = new ObjectMapper();

    /** 执行上下文：skill 之间通过它传递状态。 */
    @Data
    public static class Ctx {
        private Project project;
        private MixParams params = new MixParams();
        private List<Material> pool = new ArrayList<>();
        private String hookText;
        private String script;
        private int variant;
        private List<String> log = new ArrayList<>();

        public void log(String value) {
            log.add(value);
            if (log.size() > 200) log.remove(0);
        }
    }

    /** 内置 skill 清单（同时作为给 AI 看的能力表）。 */
    public static final List<Map<String, Object>> BUILTIN = List.of(
            skill("select_materials", "按角色/关键词从素材库挑选本次可用素材",
                    Map.of("roles", "字符串数组，可选 hook/body/product/celebrity/endcard/voice/bgm",
                            "keyword", "文件名或标签关键词，可空", "limit", "最多取多少条，默认 300")),
            skill("set_duration", "设定成片时长区间", Map.of("minSec", "下限秒数", "maxSec", "上限秒数",
                    "dense", "true 时向 100 秒收敛", "targetSec", "可直接指定")),
            skill("set_slice", "设定拆条策略", Map.of("sliceSec", "单片秒数", "jitter", "单片抖动秒数",
                    "explode", "是否把长素材拆成多片", "maxPerMaterial", "同一素材最多用几片")),
            skill("set_structure", "设定结构配比", Map.of("hookSec", "钩子秒数", "celebrityRatio", "明星素材占比 0-1",
                    "productSlots", "产品段插入次数", "productSec", "每段产品秒数", "endcard", "是否加片尾", "endcardSec", "片尾秒数", "requireDedicatedEndcard", "是否必须独立片尾卡")),
            skill("gen_hook", "用 AI 生成前 3 秒钩子文案", Map.of("extra", "额外要求，可空")),
            skill("gen_script", "用 AI 生成口播脚本", Map.of("seconds", "目标秒数")),
            skill("pick_audio", "指定人声/BGM 素材", Map.of("voiceMaterialId", "人声素材 id，可空",
                    "bgmMaterialId", "BGM 素材 id，可空", "bgmVolume", "BGM 音量 0-1")),
            skill("set_canvas", "设定画面规格", Map.of("width", "宽", "height", "高", "fps", "帧率")),
            skill("set_quality", "设定带货交付质检、AI 补素材和旧字幕清理策略", Map.of(
                    "autoUseCrawledMaterials", "兼容旧配置；新任务用 materialSourceMode",
                    "materialSourceMode", "local/builtin/extended；仅控制受限公开来源或官方授权来源，不允许绕过站点授权",
                    "projectRelevantOnly", "true 时按项目语义过滤素材",
                    "dedupStrictness", "off/standard/strict",
                    "cleanSourceSubtitles", "true 时清理素材自带旧字幕安全区",
                    "sourceSubtitleCleanMode", "off/subtitle-safe-band",
                    "autoSubtitles", "true 时烧录授权素材 ASR 字幕",
                    "burnAiVoiceCaptions", "true 时烧录 AI 口播真实 ASR 字幕")),
            skill("fetch_web_video", "从网页地址抓取视频素材入库", Map.of("url", "网页或直链地址", "role", "入库后的角色")),
            skill("fetch_audio_library", "从免费音频库检索并下载素材入库", Map.of("source", "freesound/pixabay/mixkit/all",
                    "keyword", "关键词", "limit", "下载条数", "role", "bgm 或 voice"))
    );
    private static final Set<String> BUILTIN_NAMES = BUILTIN.stream()
            .map(item -> String.valueOf(item.get("name")))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static Map<String, Object> skill(String name, String desc, Map<String, String> params) {
        return Map.of("name", name, "description", desc, "params", params);
    }

    /** 面向 Web 层的安全验证入口。 */
    public String validateCustomSkill(String defJson, String type) {
        return validateCustomSkillDef(defJson, type);
    }

    /**
     * 验证并标准化用户自定义 Skill DSL。包私有，供单元测试直接覆盖。
     * 任何 legacy command/template/url/http/shell 类定义都明确拒绝。
     */
    String validateCustomSkillDef(String defJson, String type) {
        if (!isCustomSkillType(type)) throw invalid("Skill 类型只允许 script 或 ai");
        if (defJson == null || defJson.isBlank()) throw invalid("Skill DSL 定义不能为空");
        if (defJson.length() > 20_000) throw invalid("Skill DSL 定义过长");
        final JsonNode root;
        try {
            root = om.readTree(defJson);
        } catch (Exception e) {
            throw invalid("Skill DSL 必须是合法 JSON");
        }
        if (!root.isObject()) throw invalid("Skill DSL 根节点必须是对象");
        rejectForbiddenFields(root, "def");
        if (!root.path("version").isInt() || root.path("version").asInt() != 1) {
            throw invalid("Skill DSL 仅支持 version=1");
        }
        JsonNode inputSteps = root.get("steps");
        if (inputSteps == null || !inputSteps.isArray()) throw invalid("Skill DSL steps 必须是数组");
        if (inputSteps.isEmpty() || inputSteps.size() > MAX_DSL_STEPS) {
            throw invalid("Skill DSL steps 数量必须在 1-" + MAX_DSL_STEPS + " 之间");
        }
        ObjectNode normalized = om.createObjectNode();
        normalized.put("version", 1);
        ArrayNode steps = normalized.putArray("steps");
        for (int i = 0; i < inputSteps.size(); i++) {
            steps.add(normalizeDslStep(inputSteps.get(i), i));
        }
        try {
            return om.writeValueAsString(normalized);
        } catch (Exception e) {
            throw invalid("Skill DSL 标准化失败");
        }
    }

    /** 包私有 helper：执行已验证的 DSL，避免 custom skill 落回可执行脚本。 */
    void executeCustomDsl(String normalizedDef, Ctx ctx) {
        // 即使 helper 被直接调用，也先过同一套 schema，避免绕过保存阶段验证。
        normalizedDef = validateCustomSkillDef(normalizedDef, SkillType.script.name());
        final JsonNode root;
        try {
            root = om.readTree(normalizedDef);
        } catch (Exception e) {
            throw invalid("已保存的 Skill DSL 无法解析");
        }
        for (int i = 0; i < root.path("steps").size(); i++) {
            JsonNode step = root.path("steps").get(i);
            String op = step.path("op").asText();
            try {
                switch (op) {
                    case "select_materials" -> selectMaterials(step, ctx, true);
                    case "set_params" -> applyParams(step.path("params"), ctx.getParams());
                    case "set_hook" -> ctx.setHookText(step.path("text").asText());
                    case "set_script" -> ctx.setScript(step.path("text").asText());
                    case "pick_audio" -> applyAudio(step, ctx.getParams());
                    case "note" -> ctx.log("custom note: " + step.path("text").asText());
                    default -> throw invalid("非法 DSL op: " + op);
                }
            } catch (IllegalArgumentException e) {
                ctx.log("custom skill DSL 第 " + (i + 1) + " 步拒绝: " + e.getMessage());
                throw e;
            }
        }
    }

    public MixPlanner.Plan run(String workflowDefJson, Project project, MixParams override,
                               int variant, Consumer<String> onStep, Ctx ctxOut) {
        Ctx ctx = ctxOut == null ? new Ctx() : ctxOut;
        ctx.setProject(project);
        ctx.setVariant(variant);
        Set<String> explicitOverrideFields = explicitParameterFields(override);
        if (override != null) ctx.setParams(copyParams(override).normalized());

        String normalizedWorkflow = validateWorkflowDefinition(workflowDefJson);
        List<JsonNode> steps = parseSteps(normalizedWorkflow);
        for (JsonNode step : steps) {
            String name = step.path("skill").asText(step.path("name").asText(""));
            if (name.isBlank()) continue;
            JsonNode args = step.path("args");
            if (onStep != null) onStep.accept("执行 " + name);
            try {
                invoke(name, args, ctx);
            } catch (Exception e) {
                ctx.log("[" + name + "] 失败: " + e.getMessage());
                log.warn("skill {} failed", name, e);
                // 自定义 Skill 的非法 DSL 不应被静默吞掉；内置 Skill 保留历史容错行为。
                if (!BUILTIN_NAMES.contains(name) && e instanceof IllegalArgumentException iae) throw iae;
            }
        }
        restoreExplicitParameters(ctx.getParams(), override, explicitOverrideFields);
        MixParams p = ctx.getParams().normalized();
        if (Boolean.TRUE.equals(p.getStrictFolderSequence()) && p.getFolderReadSteps() != null
                && !p.getFolderReadSteps().isEmpty()) {
            MixPlanner.Plan ordered = planner.planOrderedFolders(resolveOrderedFolderSteps(p), p, variant,
                    ctx.getHookText(), EditorialBriefService.deriveIntent(ctx.getProject()));
            ordered.getNotes().addAll(ctx.getLog());
            return ordered;
        }
        if (ctx.getPool().isEmpty()) {
            ctx.setPool(MaterialSourcePolicy.allowed(materialRepo.findAll(), p));
            ctx.log("未指定素材范围，使用允许来源素材 " + ctx.getPool().size() + " 条");
        } else if (ctx.getPool().stream().noneMatch(material -> material.getFileType() != Material.FileType.audio)) {
            List<Material> visuals = MaterialSourcePolicy.allowed(materialRepo.findAll(), p).stream()
                    .filter(material -> material.getFileType() != Material.FileType.audio)
                    .toList();
            List<Material> restored = new ArrayList<>(ctx.getPool());
            restored.addAll(visuals);
            ctx.setPool(restored);
            ctx.log("工作流只选中了音频，已自动补回 " + visuals.size() + " 条画面素材用于排版");
        }
        applyManualMaterialScope(ctx);
        p = ctx.getParams().normalized();
        ctx.setPool(MaterialSourcePolicy.allowed(ctx.getPool(), p));
        applyProjectRelevanceScope(ctx);
        String strategy = p.getHookStrategy();
        if (strategy == null || strategy.isBlank()) {
            strategy = HookStrategy.select(ctx.getProject(), variant).name();
            p.setHookStrategy(strategy);
        }
        HookStrategy hookStrategy = HookStrategy.safeValueOf(strategy);
        if ((ctx.getHookText() == null || ctx.getHookText().isBlank()) && Boolean.TRUE.equals(p.getAiHook())) {
            String manual = p.getHookText();
            ctx.setHookText(manual != null && !manual.isBlank()
                    ? manual : copyService.hook(ctx.getProject(), null, hookStrategy));
        }
        if (Boolean.TRUE.equals(p.getAutoRehook())) {
            String rehook = p.getRehookText();
            if (rehook == null || rehook.isBlank()) {
                rehook = copyService.rehook(ctx.getProject(), null, hookStrategy);
                p.setRehookText(rehook);
            }
        }
        MixPlanner.Plan plan = planner.plan(planner.buildPool(ctx.getPool(), p), p, variant, ctx.getHookText(),
                EditorialBriefService.deriveIntent(ctx.getProject()));
        plan.getNotes().addAll(ctx.getLog());
        return plan;
    }

    public void invoke(String name, JsonNode args, Ctx ctx) {
        JsonNode a = args != null && args.isObject() ? args : om.createObjectNode();
        MixParams p = ctx.getParams();
        switch (name) {
            case "select_materials" -> selectMaterials(a, ctx, false);
            case "set_duration" -> {
                ObjectNode params = om.createObjectNode();
                copyIfPresent(a, params, "minSec"); copyIfPresent(a, params, "maxSec");
                copyIfPresent(a, params, "dense"); copyIfPresent(a, params, "targetSec");
                applyParams(params, p); ctx.log("set_duration → " + p.getMinSec() + "-" + p.getMaxSec() + "s");
            }
            case "set_slice" -> {
                ObjectNode params = om.createObjectNode();
                moveIfPresent(a, params, "sliceSec", "sliceSec"); moveIfPresent(a, params, "jitter", "sliceJitter");
                moveIfPresent(a, params, "explode", "explodeLongClips"); moveIfPresent(a, params, "maxPerMaterial", "maxSlicesPerMaterial");
                applyParams(params, p); ctx.log("set_slice → " + p.getSliceSec() + "s/片");
            }
            case "set_structure" -> {
                ObjectNode params = om.createObjectNode();
                copyIfPresent(a, params, "hookSec"); copyIfPresent(a, params, "celebrityRatio");
                copyIfPresent(a, params, "productSlots"); copyIfPresent(a, params, "productSec"); copyIfPresent(a, params, "endcard");
                copyIfPresent(a, params, "endcardSec"); copyIfPresent(a, params, "requireDedicatedEndcard");
                applyParams(params, p); ctx.log("set_structure → 产品段 x" + p.getProductSlots());
            }
            case "gen_hook" -> {
                String manual = p.getHookText();
                if (manual != null && !manual.isBlank() && !a.path("force").asBoolean(false)) {
                    ctx.setHookText(manual.trim());
                } else {
                    String extra = a.path("extra").asText("");
                    ctx.setHookText(copyService.hook(ctx.getProject(), extra.isBlank() ? null : extra));
                }
            }
            case "gen_script" -> ctx.setScript(copyService.script(ctx.getProject(), a.path("seconds").asInt(60), null));
            case "pick_audio" -> applyAudio(a, p);
            case "set_canvas" -> {
                ObjectNode params = om.createObjectNode();
                copyIfPresent(a, params, "width"); copyIfPresent(a, params, "height"); copyIfPresent(a, params, "fps");
                applyParams(params, p);
            }
            case "set_quality" -> {
                ObjectNode params = om.createObjectNode();
                copyIfPresent(a, params, "autoUseCrawledMaterials"); copyIfPresent(a, params, "materialSourceMode"); copyIfPresent(a, params, "projectRelevantOnly");
                copyIfPresent(a, params, "dedupStrictness"); copyIfPresent(a, params, "cleanSourceSubtitles");
                copyIfPresent(a, params, "sourceSubtitleCleanMode"); copyIfPresent(a, params, "autoSubtitles");
                copyIfPresent(a, params, "burnAiVoiceCaptions");
                applyParams(params, p); ctx.log("set_quality → AI补素材 " + p.getAutoUseCrawledMaterials() + "，旧字幕清理 " + p.getCleanSourceSubtitles());
            }
            case "fetch_web_video" -> fetchWebVideo(a, ctx);
            case "fetch_audio_library" -> fetchAudioLibrary(a, ctx);
            default -> invokeCustomSkill(name, ctx);
        }
    }

    private void invokeCustomSkill(String name, Ctx ctx) {
        SkillDef def = skillRepo.findByName(name).orElseThrow(() -> invalid("未知 Skill: " + name));
        if (!Boolean.TRUE.equals(def.getEnabled())) throw invalid("Skill 未启用: " + name);
        if (!isCustomSkillType(def.getType())) throw invalid("Skill 类型不可调用: " + name);
        String normalized = validateCustomSkillDef(def.getDef(), def.getType());
        executeCustomDsl(normalized, ctx);
        ctx.log("自定义 Skill 已执行: " + name);
    }

    private List<MixPlanner.OrderedFolderStep> resolveOrderedFolderSteps(MixParams params) {
        List<Material> all = materialRepo.findAll();
        List<MixPlanner.OrderedFolderStep> resolved = new ArrayList<>();
        for (MixParams.FolderReadStep step : params.getFolderReadSteps()) {
            if (step == null || Boolean.FALSE.equals(step.getEnabled())) continue;
            MaterialFolder folder = step.getFolderId() == null ? null : folderRepo.findById(step.getFolderId()).orElse(null);
            if (folder == null) {
                throw invalid("第 " + step.getOrder() + " 步未选择有效的应用内文件夹");
            }
            if (Boolean.FALSE.equals(folder.getEnabled())) {
                throw invalid("第 " + step.getOrder() + " 步文件夹已停用：" + folder.getName());
            }
            step.setFolderNameSnapshot(folder.getName());
            List<Material> materials = all.stream()
                    .filter(material -> MaterialSourcePolicy.allows(material, params))
                    .filter(material -> Objects.equals(folder.getId(), material.getFolderId()))
                    .toList();
            boolean hasReadableVisual = materials.stream().anyMatch(material -> material.getFileType() != Material.FileType.audio
                    && material.getStatus() == Material.Status.ready
                    && material.getFilePath() != null
                    && (material.getFileType() == Material.FileType.image
                    || (material.getDurationSec() != null && material.getDurationSec() >= 1.0)));
            if (!hasReadableVisual && "fallback".equalsIgnoreCase(step.getShortagePolicy()) && step.getFallbackFolderId() != null) {
                MaterialFolder fallback = folderRepo.findById(step.getFallbackFolderId()).orElse(null);
                if (fallback == null || Boolean.FALSE.equals(fallback.getEnabled())) {
                    throw invalid("第 " + step.getOrder() + " 步备用文件夹不可用");
                }
                step.setFallbackFolderNameSnapshot(fallback.getName());
                materials = all.stream()
                        .filter(material -> MaterialSourcePolicy.allows(material, params))
                        .filter(material -> Objects.equals(fallback.getId(), material.getFolderId()))
                        .toList();
            }
            double target = step.getTargetSec() == null ? 0 : step.getTargetSec();
            resolved.add(new MixPlanner.OrderedFolderStep(step.getOrder(), step.getName(), materials, target,
                    !Boolean.FALSE.equals(step.getRequired())));
        }
        resolved.sort(Comparator.comparingInt(MixPlanner.OrderedFolderStep::order));
        if (resolved.isEmpty()) throw invalid("严格文件夹顺序没有可执行的读取步骤");
        return resolved;
    }

    /**
     * 人工范围优先：指定素材或目录时，计划只能从这些本地素材取片。
     * rules-only 是默认模式；cross-folder 仅代表允许在用户已选择的多个目录之间随机组合，
     * 两种模式都不会自动引入未选目录中的素材。
     */
    private void applyManualMaterialScope(Ctx ctx) {
        MixParams params = ctx.getParams();
        Set<Long> ids = params.getMaterialIds() == null ? Set.of() : new LinkedHashSet<>(params.getMaterialIds());
        Set<Long> folders = params.getFolderIds() == null ? Set.of() : new LinkedHashSet<>(params.getFolderIds());
        if (ids.isEmpty() && folders.isEmpty()) return;
        List<Material> scoped = new ArrayList<>();
        List<Material> selectedVisuals = new ArrayList<>();
        for (Material material : ctx.getPool()) {
            // Material IDs/folders narrow the visual storyline. Audio must remain available for explicitly selected
            // voice/BGM and generated narration, otherwise a visual-only selection silently removes every sound source.
            if (material.getFileType() == Material.FileType.audio) {
                scoped.add(material);
                continue;
            }
            boolean selectedById = !ids.isEmpty() && ids.contains(material.getId());
            boolean selectedByFolder = !folders.isEmpty() && folders.contains(material.getFolderId());
            if (selectedById || selectedByFolder) selectedVisuals.add(material);
        }
        if (selectedVisuals.isEmpty() && ids.isEmpty() && !folders.isEmpty()) {
            // Imported materials may not have a folderId yet. An empty folder selection must not erase a usable library.
            for (Material material : ctx.getPool()) {
                if (material.getFileType() != Material.FileType.audio) scoped.add(material);
            }
            ctx.log("所选文件夹没有画面素材，已忽略空文件夹筛选并使用全部画面素材");
        } else {
            scoped.addAll(selectedVisuals);
        }
        ctx.setPool(scoped);
        ctx.log("人工素材范围 → " + scoped.size() + " 条"
                + ("cross-folder".equalsIgnoreCase(params.getMaterialSelectionMode()) ? "（允许所选目录交叉组合）" : "（仅人工规则）"));
    }

    private void applyProjectRelevanceScope(Ctx ctx) {
        MixParams params = ctx.getParams();
        Project project = ctx.getProject();
        if (project == null || !Boolean.TRUE.equals(params.getProjectRelevantOnly())) return;
        Set<Long> explicit = params.getMaterialIds() == null ? Set.of() : new HashSet<>(params.getMaterialIds());
        Set<String> terms = projectTerms(project);
        Set<String> banned = splitTerms(project.getBannedWords());
        if (terms.isEmpty() && banned.isEmpty()) return;
        List<Material> kept = new ArrayList<>();
        int excluded = 0;
        for (Material material : ctx.getPool()) {
            String text = (String.valueOf(material.getName()) + " " + String.valueOf(material.getTags())).toLowerCase(Locale.ROOT);
            boolean bannedMatch = banned.stream().anyMatch(text::contains);
            boolean matched = terms.stream().anyMatch(text::contains);
            boolean explicitSelection = explicit.contains(material.getId());
            boolean visual = material.getFileType() != Material.FileType.audio;
            if (bannedMatch || (visual && !explicitSelection && !matched && containsUnrelatedIp(text))) {
                excluded++;
                continue;
            }
            kept.add(material);
        }
        if (!kept.isEmpty()) {
            ctx.setPool(kept);
            if (excluded > 0) ctx.log("项目相关性过滤 → 排除 " + excluded + " 条无关或禁用素材");
        } else if (excluded > 0) {
            ctx.log("项目相关性过滤后没有可用素材；已保留原人工范围，建议给素材添加项目关键词标签");
        }
    }

    private Set<String> projectTerms(Project project) {
        Set<String> terms = new LinkedHashSet<>();
        for (String value : List.of(project.getBrand(), project.getCategory(), project.getProduct(), project.getSellingPoints(), project.getAudience())) {
            terms.addAll(splitTerms(value));
        }
        terms.removeIf(term -> term.length() < 2 || term.matches("[0-9]+"));
        return terms;
    }

    private Set<String> splitTerms(String value) {
        if (value == null || value.isBlank()) return new LinkedHashSet<>();
        Set<String> terms = new LinkedHashSet<>();
        for (String term : value.toLowerCase(Locale.ROOT).split("[\\s,，、;；/|]+")) {
            String clean = term.trim();
            if (!clean.isBlank()) terms.add(clean);
        }
        return terms;
    }

    private boolean containsUnrelatedIp(String text) {
        return text.contains("喜羊羊") || text.contains("灰太狼") || text.contains("熊出没")
                || text.contains("奥特曼") || text.contains("动画") || text.contains("卡通");
    }

    private void selectMaterials(JsonNode step, Ctx ctx, boolean dsl) {
        List<MaterialRole> roles = new ArrayList<>();
        for (JsonNode role : step.path("roles")) {
            try {
                roles.add(dsl ? parseStrictRole(role.asText()) : MaterialRole.valueOf(role.asText()));
            } catch (Exception e) {
                if (dsl) throw invalid("非法素材角色: " + role.asText());
                // 保留 builtin 的历史容错：未知角色不参与筛选。
            }
        }
        String keyword = step.path("keyword").asText("");
        int limit = dsl ? boundedInt(step.get("limit"), 300, 1, 500, "limit")
                : Math.max(1, Math.min(500, step.path("limit").asInt(300)));
        List<Material> found = new ArrayList<>();
        if (roles.isEmpty()) found.addAll(materialService.search(null, null, keyword));
        else for (MaterialRole role : roles) found.addAll(materialService.search(role, null, keyword));
        Map<Long, Material> unique = new LinkedHashMap<>();
        for (Material material : found) unique.put(material.getId(), material);
        List<Material> selected = new ArrayList<>(unique.values());
        if (selected.size() > limit) selected = selected.subList(0, limit);
        ctx.getPool().addAll(selected);
        ctx.log("select_materials → " + selected.size() + " 条");
    }

    private void applyAudio(JsonNode step, MixParams params) {
        ObjectNode patch = om.createObjectNode();
        copyIfPresent(step, patch, "voiceMaterialId"); copyIfPresent(step, patch, "bgmMaterialId");
        copyIfPresent(step, patch, "bgmVolume"); copyIfPresent(step, patch, "hookAudioMaterialId");
        copyIfPresent(step, patch, "autoMatchAudio"); copyIfPresent(step, patch, "hookAudioVolume");
        applyParams(patch, params);
    }

    private void fetchWebVideo(JsonNode a, Ctx ctx) {
        String url = UrlGuard.validate(a.path("url").asText(""));
        CrawlerGateway.FetchResult fetched = crawler.fetchVideo(url);
        if (!fetched.isOk()) { ctx.log("fetch_web_video 失败: " + fetched.getMessage()); return; }
        Material material = materialService.registerDownloaded(fetched.getFilePath(), url, parseStrictRole(a.path("role").asText("body")));
        ctx.getPool().add(material);
        ctx.log("fetch_web_video → " + material.getName());
    }

    private void fetchAudioLibrary(JsonNode a, Ctx ctx) {
        String source = a.path("source").asText("all");
        String keyword = a.path("keyword").asText("");
        int limit = Math.min(10, Math.max(1, a.path("limit").asInt(3)));
        MaterialRole role = parseStrictRole(a.path("role").asText("bgm"));
        int imported = 0;
        for (CrawlerGateway.RemoteItem item : crawler.searchAudio(source, keyword, limit)) {
            if (imported >= limit || "blocked".equals(item.getLicense())) continue;
            CrawlerGateway.FetchResult fetched = crawler.fetchRemoteItem(item);
            if (fetched.isOk()) { ctx.getPool().add(materialService.registerDownloaded(fetched.getFilePath(), item.getPageUrl(), role)); imported++; }
        }
        ctx.log("fetch_audio_library → 入库 " + imported + " 条");
    }

    /**
     * Validates every persisted workflow definition and returns a canonical JSON form.
     * The same validator is used by the editor, imports, AI plans, and the execution path so
     * unknown steps and ignored arguments cannot be stored as seemingly runnable workflows.
     */
    public String validateWorkflowDefinition(String workflowDefJson) {
        if (workflowDefJson == null || workflowDefJson.isBlank()) throw invalid("工作流定义不能为空");
        final JsonNode root;
        try {
            root = om.readTree(workflowDefJson);
        } catch (Exception e) {
            throw invalid("工作流定义必须是合法 JSON");
        }
        JsonNode inputSteps = root != null && root.isArray() ? root : root == null ? null : root.get("steps");
        if (inputSteps == null || !inputSteps.isArray()) throw invalid("工作流定义必须包含 steps 数组");
        if (inputSteps.isEmpty() || inputSteps.size() > MAX_DSL_STEPS) {
            throw invalid("工作流步骤数量必须在 1-" + MAX_DSL_STEPS + " 之间");
        }
        ObjectNode normalized = om.createObjectNode();
        ArrayNode steps = normalized.putArray("steps");
        for (int i = 0; i < inputSteps.size(); i++) {
            steps.add(normalizeWorkflowStep(inputSteps.get(i), i));
        }
        try {
            return om.writeValueAsString(normalized);
        } catch (Exception e) {
            throw invalid("工作流定义标准化失败");
        }
    }

    private ObjectNode normalizeWorkflowStep(JsonNode input, int index) {
        if (input == null || !input.isObject()) throw invalid("工作流第 " + (index + 1) + " 步必须是对象");
        String skill = input.path("skill").asText("").trim();
        String legacyName = input.path("name").asText("").trim();
        if (skill.isEmpty()) skill = legacyName;
        if (skill.isEmpty()) throw invalid("工作流第 " + (index + 1) + " 步缺少 skill");
        if (!legacyName.isEmpty() && !legacyName.equals(skill)) throw invalid("工作流第 " + (index + 1) + " 步的 skill 与 name 不一致");
        JsonNode args = input.get("args");
        if (args == null || !args.isObject()) throw invalid("工作流 " + skill + " 的 args 必须是对象");
        rejectUnknown(input, Set.of("skill", "name", "args"), "工作流步骤");

        ObjectNode out = om.createObjectNode();
        out.put("skill", skill);
        out.set("args", normalizeWorkflowArgs(skill, args));
        return out;
    }

    private ObjectNode normalizeWorkflowArgs(String skill, JsonNode args) {
        ObjectNode out = om.createObjectNode();
        switch (skill) {
            case "select_materials" -> {
                rejectUnknown(args, Set.of("roles", "keyword", "limit"), skill);
                if (args.has("roles")) {
                    if (!args.path("roles").isArray() || args.path("roles").size() > 8) throw invalid("select_materials.roles 数量非法");
                    ArrayNode roles = out.putArray("roles");
                    Set<MaterialRole> seen = EnumSet.noneOf(MaterialRole.class);
                    for (JsonNode value : args.path("roles")) {
                        MaterialRole role = parseStrictRole(value.asText());
                        if (seen.add(role)) roles.add(role.name());
                    }
                }
                if (args.has("keyword")) out.put("keyword", normalizedOptionalText(requireTextValue(args.get("keyword"), "keyword"), MAX_KEYWORD, "keyword"));
                if (args.has("limit")) out.put("limit", boundedInt(args.get("limit"), 300, 1, 500, "limit"));
            }
            case "set_duration" -> copyWorkflowParams(args, out, Set.of("minSec", "maxSec", "dense", "targetSec"), skill);
            case "set_slice" -> {
                rejectUnknown(args, Set.of("sliceSec", "jitter", "sliceJitter", "explode", "explodeLongClips", "maxPerMaterial", "maxSlicesPerMaterial"), skill);
                copyAliasedWorkflowParam(args, out, "sliceSec", "sliceSec");
                copyAliasedWorkflowParam(args, out, "jitter", "sliceJitter");
                copyAliasedWorkflowParam(args, out, "explode", "explodeLongClips");
                copyAliasedWorkflowParam(args, out, "maxPerMaterial", "maxSlicesPerMaterial");
                if (out.isEmpty()) throw invalid("set_slice 至少提供一个参数");
            }
            case "set_structure" -> copyWorkflowParams(args, out, Set.of("hookSec", "celebrityRatio", "productSlots", "productSec", "endcard"), skill);
            case "set_canvas" -> copyWorkflowParams(args, out, Set.of("width", "height", "fps"), skill);
            case "set_quality" -> copyWorkflowParams(args, out, Set.of("autoUseCrawledMaterials", "projectRelevantOnly", "dedupStrictness", "cleanSourceSubtitles", "sourceSubtitleCleanMode", "autoSubtitles", "burnAiVoiceCaptions"), skill);
            case "pick_audio" -> {
                rejectUnknown(args, Set.of("voiceMaterialId", "bgmMaterialId", "bgmVolume", "hookAudioMaterialId", "autoMatchAudio", "hookAudioVolume"), skill);
                if (args.isEmpty()) throw invalid("pick_audio 至少提供一个音频参数");
                copyWorkflowParams(args, out, Set.of("voiceMaterialId", "bgmMaterialId", "bgmVolume", "hookAudioMaterialId", "autoMatchAudio", "hookAudioVolume"), skill);
            }
            case "gen_hook" -> {
                rejectUnknown(args, Set.of("extra", "force"), skill);
                if (args.has("extra")) out.put("extra", normalizedOptionalText(requireTextValue(args.get("extra"), "extra"), MAX_TEXT, "extra"));
                if (args.has("force")) out.put("force", strictBoolean(args.get("force"), "force"));
            }
            case "gen_script" -> {
                rejectUnknown(args, Set.of("seconds"), skill);
                if (args.has("seconds")) out.put("seconds", boundedInt(args.get("seconds"), 60, 5, 300, "seconds"));
            }
            case "fetch_web_video" -> {
                rejectUnknown(args, Set.of("url", "role"), skill);
                if (!args.has("url")) throw invalid("fetch_web_video 必须提供 URL");
                out.put("url", UrlGuard.validate(requireTextValue(args.get("url"), "url")));
                out.put("role", args.has("role") ? parseStrictRole(args.path("role").asText()).name() : MaterialRole.body.name());
            }
            case "fetch_audio_library" -> {
                rejectUnknown(args, Set.of("source", "keyword", "limit", "role"), skill);
                String source = args.path("source").asText("all");
                if (!Set.of("freesound", "pixabay", "mixkit", "all").contains(source)) throw invalid("fetch_audio_library.source 不支持");
                String role = args.path("role").asText("bgm");
                if (!MaterialRole.bgm.name().equals(role) && !MaterialRole.voice.name().equals(role)) throw invalid("fetch_audio_library.role 只允许 bgm 或 voice");
                out.put("source", source);
                out.put("keyword", normalizedOptionalText(args.path("keyword").asText(""), MAX_KEYWORD, "keyword"));
                out.put("limit", boundedInt(args.get("limit"), 3, 1, 10, "limit"));
                out.put("role", role);
            }
            default -> normalizeCustomWorkflowSkill(skill, args, out);
        }
        return out;
    }

    private void copyWorkflowParams(JsonNode args, ObjectNode out, Set<String> allowed, String skill) {
        rejectUnknown(args, allowed, skill);
        if (args.isEmpty()) throw invalid(skill + " 至少提供一个参数");
        ObjectNode patch = om.createObjectNode();
        allowed.stream().filter(args::has).forEach(key -> patch.set(key, args.get(key)));
        ObjectNode normalized = normalizeParams(patch);
        normalized.fields().forEachRemaining(entry -> out.set(entry.getKey(), entry.getValue()));
    }

    private void copyAliasedWorkflowParam(JsonNode args, ObjectNode out, String canonical, String alias) {
        JsonNode primary = args.get(canonical);
        JsonNode legacy = args.get(alias);
        if (primary != null && legacy != null && !primary.equals(legacy)) {
            throw invalid("set_slice 参数 " + canonical + " 与 " + alias + " 不一致");
        }
        JsonNode value = primary != null ? primary : legacy;
        if (value == null) return;
        ObjectNode patch = om.createObjectNode();
        String param = switch (canonical) {
            case "jitter" -> "sliceJitter";
            case "explode" -> "explodeLongClips";
            case "maxPerMaterial" -> "maxSlicesPerMaterial";
            default -> canonical;
        };
        patch.set(param, value);
        JsonNode normalized = normalizeParams(patch).get(param);
        out.set(canonical, normalized);
    }

    private void normalizeCustomWorkflowSkill(String skill, JsonNode args, ObjectNode out) {
        if (BUILTIN_NAMES.contains(skill)) throw invalid("工作流 Skill 参数无效: " + skill);
        if (!args.isEmpty()) throw invalid("自定义 Skill 不接受 args: " + skill);
        if (skillRepo == null) throw invalid("无法验证自定义 Skill: " + skill);
        SkillDef definition = skillRepo.findByName(skill).orElseThrow(() -> invalid("未知 Skill: " + skill));
        if (!Boolean.TRUE.equals(definition.getEnabled()) || !isCustomSkillType(definition.getType())) {
            throw invalid("Skill 未启用或不可调用: " + skill);
        }
        validateCustomSkillDef(definition.getDef(), definition.getType());
    }

    /** AI 只能看到内置项的完整参数与已启用自定义项的名称/说明。 */
    public String aiPlan(Project project, String requirement) {
        List<Map<String, Object>> callable = new ArrayList<>(BUILTIN);
        for (SkillDef skill : skillRepo.findByEnabledTrue()) {
            if (isCustomSkillType(skill.getType()) && isSafeCallableCustom(skill)) {
                callable.add(Map.of("name", skill.getName(), "description", skill.getDescription().trim()));
            }
        }
        String sys = """
                你是短视频批量混剪工作流编排器。只输出 JSON：{"steps":[{"skill":"...","args":{...}}]}。
                你只能调用提供的名称；每步 args 必须是对象；最多 30 步。
                自定义 Skill 是受约束 DSL，不能通过 args 写命令、模板、URL、HTTP、下载或 shell。
                fetch_web_video 的 url 必须是公开 http/https URL。不要编排素材库不存在的角色。
                """;
        StringBuilder user = new StringBuilder("可调用 Skill：\n");
        try { user.append(om.writerWithDefaultPrettyPrinter().writeValueAsString(callable)); } catch (Exception ignored) { }
        user.append("\n素材库现状：\n");
        try { user.append(om.writeValueAsString(materialService.stats())); } catch (Exception ignored) { }
        if (project != null) user.append("\n项目信息：品牌=").append(nz(project.getBrand())).append("；品类=")
                .append(nz(project.getCategory())).append("；产品=").append(nz(project.getProduct())).append("；卖点=")
                .append(nz(project.getSellingPoints())).append("；人群=").append(nz(project.getAudience()));
        if (requirement != null && !requirement.isBlank()) user.append("\n本次要求：").append(requirement.substring(0, Math.min(1000, requirement.length())));
        JsonNode response = aiService.askJson(UseCase.general, sys, user.toString(), 0.5, 1800,
                project == null ? null : project.getRouteOverrides());
        try {
            String validated = validateAiWorkflow(response, callable.stream().map(item -> String.valueOf(item.get("name"))).collect(java.util.stream.Collectors.toSet()));
            return validateWorkflowDefinition(validated);
        } catch (IllegalArgumentException e) {
            log.warn("AI 编排被拒绝并回退: {}", e.getMessage());
            return defaultWorkflowDef();
        }
    }

    private void validateBuiltinAiArgs(String name, JsonNode args) {
        if ("fetch_web_video".equals(name)) {
            if (!args.has("url") || !args.path("url").isTextual()) throw invalid("fetch_web_video 必须提供 URL");
            ((ObjectNode) args).put("url", UrlGuard.validate(args.path("url").asText()));
            if (args.has("role")) parseStrictRole(args.path("role").asText());
        }
        if ("select_materials".equals(name) && args.has("roles")) {
            if (!args.path("roles").isArray() || args.path("roles").size() > 8) throw invalid("select_materials.roles 数量非法");
            for (JsonNode role : args.path("roles")) parseStrictRole(role.asText());
        }
    }

    /** 包私有 helper：验证 AI 返回，防止模型绕过受约束的调用面。 */
    String validateAiWorkflow(JsonNode root, Set<String> allowedNames) {
        if (root == null || !root.isObject() || !root.path("steps").isArray()) throw invalid("AI 返回缺少 steps 数组");
        if (root.path("steps").isEmpty() || root.path("steps").size() > MAX_DSL_STEPS) {
            throw invalid("AI 返回步骤数量必须在 1-" + MAX_DSL_STEPS + " 之间");
        }
        ObjectNode normalized = om.createObjectNode();
        ArrayNode steps = normalized.putArray("steps");
        for (int i = 0; i < root.path("steps").size(); i++) {
            JsonNode step = root.path("steps").get(i);
            if (!step.isObject()) throw invalid("AI 第 " + (i + 1) + " 步不是对象");
            String name = step.path("skill").asText("");
            if (!allowedNames.contains(name)) throw invalid("AI 使用了未授权 Skill: " + name);
            JsonNode args = step.get("args");
            if (args == null || !args.isObject()) throw invalid("AI Skill args 必须是对象");
            // 自定义 Skill 的所有可执行含义均封装在已验证 DSL 中，AI 不得用 workflow args 扩展它。
            if (!BUILTIN_NAMES.contains(name)) {
                if (!args.isEmpty()) throw invalid("自定义 Skill 不接受 args: " + name);
            } else {
                validateBuiltinAiArgs(name, args);
            }
            ObjectNode output = steps.addObject(); output.put("skill", name); output.set("args", args.deepCopy());
        }
        try { return om.writeValueAsString(normalized); } catch (Exception e) { throw invalid("AI 返回序列化失败"); }
    }

    public String defaultWorkflowDef() {
        ObjectNode root = om.createObjectNode(); ArrayNode steps = root.putArray("steps");
        add(steps, "select_materials", o -> { ArrayNode r = o.putArray("roles"); for (String role : List.of("hook", "body", "celebrity", "product", "endcard", "voice", "bgm")) r.add(role); o.put("limit", 500); });
        add(steps, "set_duration", o -> { o.put("minSec", 50); o.put("maxSec", 150); o.put("dense", true); });
        add(steps, "set_slice", o -> { o.put("sliceSec", 3); o.put("jitter", 0.4); o.put("explode", true); o.put("maxPerMaterial", 5); });
        add(steps, "set_structure", o -> { o.put("hookSec", 3); o.put("celebrityRatio", 0.25); o.put("productSlots", 3); o.put("productSec", 3); o.put("endcard", true); });
        add(steps, "set_canvas", o -> { o.put("width", 1080); o.put("height", 1920); o.put("fps", 30); });
        add(steps, "set_quality", o -> { o.put("autoUseCrawledMaterials", true); o.put("projectRelevantOnly", true); o.put("dedupStrictness", "strict"); o.put("cleanSourceSubtitles", false); o.put("sourceSubtitleCleanMode", "off"); o.put("burnAiVoiceCaptions", true); });
        add(steps, "gen_hook", o -> o.put("extra", "")); add(steps, "pick_audio", o -> o.put("bgmVolume", 0.22));
        try { return om.writeValueAsString(root); } catch (Exception e) { return "{\"steps\":[]}"; }
    }

    private ObjectNode normalizeDslStep(JsonNode input, int index) {
        if (!input.isObject()) throw invalid("Skill DSL 第 " + (index + 1) + " 步必须是对象");
        String op = input.path("op").asText("");
        if (!DSL_OPS.contains(op)) throw invalid("非法 Skill DSL op: " + op);
        rejectForbiddenFields(input, "steps[" + index + "]");
        ObjectNode out = om.createObjectNode(); out.put("op", op);
        switch (op) {
            case "select_materials" -> {
                JsonNode roles = input.get("roles");
                if (roles == null || !roles.isArray() || roles.isEmpty() || roles.size() > 8) throw invalid("select_materials.roles 必须是 1-8 个合法角色");
                ArrayNode normalizedRoles = out.putArray("roles");
                Set<MaterialRole> seen = EnumSet.noneOf(MaterialRole.class);
                for (JsonNode role : roles) { MaterialRole parsed = parseStrictRole(role.asText()); if (seen.add(parsed)) normalizedRoles.add(parsed.name()); }
                out.put("keyword", normalizedOptionalText(input.path("keyword").asText(""), MAX_KEYWORD, "keyword"));
                out.put("limit", boundedInt(input.path("limit"), 300, 1, 500, "limit"));
                rejectUnknown(input, Set.of("op", "roles", "keyword", "limit"), op);
            }
            case "set_params" -> {
                JsonNode params = input.get("params");
                if (params == null || !params.isObject() || params.isEmpty()) throw invalid("set_params.params 必须是非空对象");
                out.set("params", normalizeParams(params)); rejectUnknown(input, Set.of("op", "params"), op);
            }
            case "set_hook", "set_script", "note" -> {
                out.put("text", normalizedText(requireText(input, "text"), MAX_TEXT, "text")); rejectUnknown(input, Set.of("op", "text"), op);
            }
            case "pick_audio" -> {
                if (!input.has("bgmMaterialId") && !input.has("voiceMaterialId") && !input.has("bgmVolume")
                        && !input.has("hookAudioMaterialId") && !input.has("autoMatchAudio") && !input.has("hookAudioVolume")) {
                    throw invalid("pick_audio 至少提供一个音频参数");
                }
                if (input.has("bgmMaterialId")) out.put("bgmMaterialId", positiveLong(input.get("bgmMaterialId"), "bgmMaterialId"));
                if (input.has("voiceMaterialId")) out.put("voiceMaterialId", positiveLong(input.get("voiceMaterialId"), "voiceMaterialId"));
                if (input.has("bgmVolume")) out.put("bgmVolume", boundedDouble(input.get("bgmVolume"), 0, 1, "bgmVolume"));
                if (input.has("hookAudioMaterialId")) out.put("hookAudioMaterialId", positiveLong(input.get("hookAudioMaterialId"), "hookAudioMaterialId"));
                if (input.has("autoMatchAudio")) out.put("autoMatchAudio", strictBoolean(input.get("autoMatchAudio"), "autoMatchAudio"));
                if (input.has("hookAudioVolume")) out.put("hookAudioVolume", boundedDouble(input.get("hookAudioVolume"), 0, 1, "hookAudioVolume"));
                rejectUnknown(input, Set.of("op", "bgmMaterialId", "voiceMaterialId", "bgmVolume", "hookAudioMaterialId", "autoMatchAudio", "hookAudioVolume"), op);
            }
            default -> throw invalid("非法 Skill DSL op: " + op);
        }
        return out;
    }

    private ObjectNode normalizeParams(JsonNode input) {
        ObjectNode out = om.createObjectNode();
        input.fieldNames().forEachRemaining(key -> {
            if (!MIX_PARAM_FIELDS.contains(key)) throw invalid("set_params 不允许参数: " + key);
            JsonNode value = input.get(key);
            switch (key) {
                case "minSec", "maxSec", "targetSec" -> out.put(key, boundedInt(value, 50, 5, 300, key));
                case "maxSlicesPerMaterial" -> out.put(key, boundedInt(value, 3, 1, 20, key));
                case "productSlots" -> out.put(key, boundedInt(value, 3, 0, 10, key));
                case "width", "height" -> out.put(key, boundedInt(value, 1080, 240, 3840, key));
                case "hookFontSize" -> out.put(key, boundedInt(value, 64, 12, 200, key));
                case "sliceSec" -> out.put(key, boundedDouble(value, 0.8, 15, key));
                case "sliceJitter" -> out.put(key, boundedDouble(value, 0, 5, key));
                case "hookSec" -> out.put(key, boundedDouble(value, 0, 15, key));
                case "celebrityRatio" -> out.put(key, boundedDouble(value, 0, 0.8, key));
                case "productSec" -> out.put(key, boundedDouble(value, 0.8, 15, key));
                case "fps" -> out.put(key, boundedDouble(value, 12, 60, key));
                case "bgmVolume", "hookAudioVolume" -> out.put(key, boundedDouble(value, 0, 1, key));
                case "bgmMaterialId", "voiceMaterialId", "hookAudioMaterialId", "seed" -> out.put(key, positiveLong(value, key));
                case "dense", "explodeLongClips", "endcard", "aiHook", "burnHookText", "autoMatchAudio", "autoSubtitles", "burnAiVoiceCaptions", "cleanSourceSubtitles", "projectRelevantOnly", "autoUseCrawledMaterials" -> out.put(key, strictBoolean(value, key));
                case "dedupStrictness" -> {
                    String text = normalizedText(requireTextValue(value, key), 20, key);
                    if (!Set.of("off", "standard", "strict").contains(text)) throw invalid("dedupStrictness 只允许 off/standard/strict");
                    out.put(key, text);
                }
                case "sourceSubtitleCleanMode" -> {
                    String text = normalizedText(requireTextValue(value, key), 40, key);
                    if (!Set.of("off", "subtitle-safe-band").contains(text)) throw invalid("sourceSubtitleCleanMode 只允许 off/subtitle-safe-band");
                    out.put(key, text);
                }
                case "audioMode" -> {
                    String text = normalizedText(requireTextValue(value, key), 40, key);
                    if (!Set.of("material-audio", "original", "ai-voice", "silent").contains(text)) throw invalid("audioMode 只允许 material-audio/original/ai-voice/silent");
                    out.put(key, text);
                }
                case "ttsVoice", "hookText" -> out.put(key, normalizedText(requireTextValue(value, key), MAX_TEXT, key));
                case "hookFontColor" -> out.put(key, normalizedText(requireTextValue(value, key), 40, key));
                case "namePrefix" -> out.put(key, normalizedText(requireTextValue(value, key), 80, key));
                case "materialIds", "folderIds" -> out.set(key, normalizeIdList(value, key));
                default -> throw invalid("set_params 不允许参数: " + key);
            }
        });
        return out;
    }

    private void applyParams(JsonNode patch, MixParams target) {
        ObjectNode normalized = normalizeParams(patch);
        try { om.readerForUpdating(target).readValue(normalized); } catch (Exception e) { throw invalid("MixParams 合并失败"); }
        target.normalized();
    }

    private Set<String> explicitParameterFields(MixParams override) {
        if (override == null) return Set.of();
        JsonNode tree = om.valueToTree(override);
        Set<String> fields = new HashSet<>();
        tree.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private void restoreExplicitParameters(MixParams target, MixParams override, Set<String> fields) {
        if (target == null || override == null || fields.isEmpty()) return;
        ObjectNode source = om.valueToTree(override);
        ObjectNode patch = om.createObjectNode();
        fields.forEach(field -> {
            JsonNode value = source.get(field);
            if (value != null && !value.isNull()) patch.set(field, value);
        });
        if (!patch.isEmpty()) {
            try { om.readerForUpdating(target).readValue(patch); }
            catch (Exception e) { throw invalid("MixParams 显式参数恢复失败"); }
        }
        target.normalized();
    }

    private MixParams copyParams(MixParams source) {
        try { return om.readValue(om.writeValueAsString(source), MixParams.class); }
        catch (Exception e) { return source; }
    }

    private ArrayNode normalizeIdList(JsonNode value, String field) {
        if (!value.isArray() || value.isEmpty() || value.size() > 500) throw invalid(field + " 必须是 1-500 个正整数");
        ArrayNode out = om.createArrayNode(); Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : value) ids.add(positiveLong(item, field));
        ids.forEach(out::add); return out;
    }

    private void rejectForbiddenFields(JsonNode node, String path) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (FORBIDDEN_FIELD.matcher(entry.getKey()).matches()) throw invalid("Skill DSL 禁止字段: " + path + "." + entry.getKey());
                rejectForbiddenFields(entry.getValue(), path + "." + entry.getKey());
            });
        } else if (node.isArray()) for (int i = 0; i < node.size(); i++) rejectForbiddenFields(node.get(i), path + "[" + i + "]");
    }

    private void rejectUnknown(JsonNode input, Set<String> allowed, String op) {
        input.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(op + " 不允许字段: " + key); });
    }
    private String requireText(JsonNode input, String key) { return requireTextValue(input.get(key), key); }
    private String requireTextValue(JsonNode value, String field) { if (value == null || !value.isTextual()) throw invalid(field + " 必须是字符串"); return value.asText(); }
    private String normalizedText(String value, int max, String field) { String text = value == null ? "" : value.trim(); if (text.isEmpty() || text.length() > max) throw invalid(field + " 长度必须在 1-" + max + " 之间"); return text; }
    private String normalizedOptionalText(String value, int max, String field) { String text = value == null ? "" : value.trim(); if (text.length() > max) throw invalid(field + " 不能超过 " + max + " 字"); return text; }
    private int boundedInt(JsonNode value, int fallback, int min, int max, String field) { if (value == null || value.isMissingNode()) return fallback; if (!value.isIntegralNumber() || value.asLong() < min || value.asLong() > max) throw invalid(field + " 必须是 " + min + "-" + max + " 的整数"); return value.asInt(); }
    private double boundedDouble(JsonNode value, double min, double max, String field) { if (value == null || !value.isNumber() || value.asDouble() < min || value.asDouble() > max) throw invalid(field + " 必须是 " + min + "-" + max + " 的数字"); return value.asDouble(); }
    private long positiveLong(JsonNode value, String field) { if (value == null || !value.isIntegralNumber() || value.asLong() <= 0) throw invalid(field + " 必须是正整数"); return value.asLong(); }
    private boolean strictBoolean(JsonNode value, String field) { if (value == null || !value.isBoolean()) throw invalid(field + " 必须是布尔值"); return value.asBoolean(); }
    private MaterialRole parseStrictRole(String value) { try { MaterialRole role = MaterialRole.valueOf(value); if (role == MaterialRole.none) throw invalid("非法素材角色: " + value); return role; } catch (IllegalArgumentException e) { throw invalid("非法素材角色: " + value); } }
    private boolean isCustomSkillType(String type) { return SkillType.script.name().equals(type) || SkillType.ai.name().equals(type); }
    private boolean isSafeCallableCustom(SkillDef skill) { try { validateCustomSkillDef(skill.getDef(), skill.getType()); return skill.getName() != null && skill.getName().matches("[A-Za-z][A-Za-z0-9_-]{0," + (MAX_SKILL_NAME - 1) + "}") && skill.getDescription() != null && !skill.getDescription().isBlank(); } catch (IllegalArgumentException e) { log.warn("跳过非法自定义 Skill {}: {}", skill.getName(), e.getMessage()); return false; } }
    private void copyIfPresent(JsonNode from, ObjectNode to, String key) { if (from.has(key)) to.set(key, from.get(key)); }
    private void moveIfPresent(JsonNode from, ObjectNode to, String fromKey, String toKey) { if (from.has(fromKey)) to.set(toKey, from.get(fromKey)); }
    private void add(ArrayNode steps, String skill, Consumer<ObjectNode> filler) { ObjectNode step = steps.addObject(); step.put("skill", skill); filler.accept(step.putObject("args")); }
    private List<JsonNode> parseSteps(String defJson) { List<JsonNode> out = new ArrayList<>(); if (defJson == null || defJson.isBlank()) return out; try { JsonNode root = om.readTree(defJson); JsonNode steps = root.isArray() ? root : root.path("steps"); if (steps.isArray()) steps.forEach(out::add); } catch (Exception e) { log.warn("工作流定义解析失败: {}", e.getMessage()); } return out; }
    private String nz(String value) { return value == null ? "" : value; }
    private IllegalArgumentException invalid(String message) { log.warn("Skill DSL / workflow validation rejected: {}", message); return new IllegalArgumentException(message); }
}
