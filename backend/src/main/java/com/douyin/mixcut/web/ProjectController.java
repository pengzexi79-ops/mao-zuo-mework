package com.douyin.mixcut.web;

import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.UseCase;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.service.AiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectRepo repo;
    private final AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Fallback draft fields used when AI is unavailable. Never persist anything. */
    private static final Map<String, String> FALLBACK_FIELDS = Map.of(
            "name", "我的投放项目",
            "brand", "未填写品牌",
            "category", "美妆",
            "product", "产品名称",
            "sellingPoints", "核心卖点一\n核心卖点二",
            "audience", "25-35岁 女性 上班族",
            "tone", "真诚测评",
            "bannedWords", "最,第一,顶级,国家级,根治,永久,100%,绝对",
            "extraPrompt", ""
    );

    private static final String FALLBACK_DEFAULT_PARAMS = """
            {"minSec":50,"maxSec":150,"dense":true,"sliceSec":3,"productSlots":3,"celebrityRatio":0.25,"width":1080,"height":1920}
            """.trim();

    @GetMapping
    public R<List<Project>> list() {
        return R.ok(repo.findAll());
    }

    @GetMapping("/{id}")
    public R<Project> get(@PathVariable Long id) {
        return repo.findById(id).map(R::ok).orElseGet(() -> R.fail("项目不存在"));
    }

    @PostMapping
    public R<Project> create(@RequestBody Project p) {
        if (p == null || p.getName() == null || p.getName().isBlank()) return R.fail("项目名不能为空");
        p.setId(null);
        p.setName(p.getName().trim());
        p.setIsBuiltin(false);
        return R.ok(repo.save(p));
    }

    /** Copies a read-only built-in template into an editable project without altering the template. */
    @PostMapping("/{id}/duplicate")
    public R<Project> duplicate(@PathVariable Long id) {
        Project source = repo.findById(id).orElse(null);
        if (source == null) return R.fail("项目不存在");
        Project copy = new Project();
        copy.setName(source.getName() + " 副本");
        copy.setBrand(source.getBrand());
        copy.setCategory(source.getCategory());
        copy.setProduct(source.getProduct());
        copy.setSellingPoints(source.getSellingPoints());
        copy.setAudience(source.getAudience());
        copy.setTone(source.getTone());
        copy.setBannedWords(source.getBannedWords());
        copy.setExtraPrompt(source.getExtraPrompt());
        copy.setDefaultParams(source.getDefaultParams());
        copy.setRouteOverrides(source.getRouteOverrides());
        copy.setIsBuiltin(false);
        return R.ok(repo.save(copy));
    }

    /**
     * 逐字段更新。这里刻意不用整体覆盖：
     * 之前踩过坑——前端只改了品牌名，整体 PUT 把 defaultParams 冲成 null，
     * 客户配了半天的出片参数一键消失。
     */
    @PutMapping("/{id}")
    public R<Project> update(@PathVariable Long id, @RequestBody Project in) {
        Project p = repo.findById(id).orElse(null);
        if (p == null) return R.fail("项目不存在");
        if (Boolean.TRUE.equals(p.getIsBuiltin())) return R.fail("内置项目模板不可修改，请先复制为新项目");
        if (in == null) return R.fail("项目请求不能为空");
        if (in.getName() != null) p.setName(in.getName());
        if (in.getBrand() != null) p.setBrand(in.getBrand());
        if (in.getCategory() != null) p.setCategory(in.getCategory());
        if (in.getProduct() != null) p.setProduct(in.getProduct());
        if (in.getSellingPoints() != null) p.setSellingPoints(in.getSellingPoints());
        if (in.getAudience() != null) p.setAudience(in.getAudience());
        if (in.getTone() != null) p.setTone(in.getTone());
        if (in.getBannedWords() != null) p.setBannedWords(in.getBannedWords());
        if (in.getExtraPrompt() != null) p.setExtraPrompt(in.getExtraPrompt());
        if (in.getDefaultParams() != null) p.setDefaultParams(in.getDefaultParams());
        if (in.getRouteOverrides() != null) p.setRouteOverrides(in.getRouteOverrides());
        return R.ok(repo.save(p));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Project project = repo.findById(id).orElse(null);
        if (project != null && Boolean.TRUE.equals(project.getIsBuiltin())) {
            return R.fail("内置项目模板不可删除，请先复制为新项目");
        }
        repo.deleteById(id);
        return R.ok();
    }

    // ---------------- AI 辅助起草项目 ----------------

    @Data
    public static class DraftReq { private String requirement; }

    @Data
    public static class DraftResp {
        private String name;
        private String brand;
        private String category;
        private String product;
        private String sellingPoints;
        private String audience;
        private String tone;
        private String bannedWords;
        private String extraPrompt;
        /** Default render params as a pre-serialized JSON string, same shape as Project.defaultParams. */
        private String defaultParams;
        /** True when AI generated this draft; false when the fallback was used. */
        private boolean aiGenerated;
    }

    /**
     * AI-assisted project draft. Returns only editable draft fields — never persists anything.
     * Falls back to sensible defaults when AI is unavailable.
     */
    @PostMapping("/draft")
    public R<DraftResp> draft(@RequestBody DraftReq req) {
        String requirement = req == null ? "" : trimTo(req.getRequirement(), 1000);

        if (!aiService.ready()) {
            log.info("project-draft: AI not configured, using local fallback");
            return R.ok(buildFallback(requirement));
        }

        try {
            return R.ok(buildAiDraft(requirement));
        } catch (Exception e) {
            log.warn("project-draft: AI call failed, using local fallback: {}", e.getMessage());
            return R.ok(buildFallback(requirement));
        }
    }

    private DraftResp buildAiDraft(String requirement) {
        String sys = """
                你是短视频带货项目策划师，服务美妆/护肤/食品类品牌。
                根据用户需求生成一个项目草稿。草稿只包含以下字段，字段必须用中文填写：
                - name: 项目名称（12字以内，简洁有记忆点）
                - brand: 品牌名
                - category: 品类（从：美妆、护肤、食品饮料、母婴、3C 数码、家清日用、服饰、保健品 中选一个最匹配的）
                - product: 产品名称
                - sellingPoints: 核心卖点，一行一个，3-5条，每条15字以内
                - audience: 目标人群画像
                - tone: 视频语气（从：真诚测评、闺蜜安利、专业成分党、搞笑玩梗、高级质感、急促带货 中选一个）
                - bannedWords: 避免使用的词，逗号分隔
                - extraPrompt: 补充提示词（可为空）
                - defaultParams: 默认出片参数 JSON，固定为 {"minSec":50,"maxSec":150,"dense":true,"sliceSec":3,"productSlots":3,"celebrityRatio":0.25,"width":1080,"height":1920}
                
                严格只输出 JSON，不要任何解释文字、不要 markdown 代码块标记。
                输出格式：{"name":"...","brand":"...","category":"...","product":"...","sellingPoints":"...","audience":"...","tone":"...","bannedWords":"...","extraPrompt":"...","defaultParams":{...}}
                """;

        String user = requirement.isBlank()
                ? "请为通用美妆护肤产品生成一个项目草稿"
                : "需求：" + requirement + "\n请据此生成项目草稿";

        AiService.Answer answer = aiService.ask(UseCase.product, sys, user, 0.7, 1200, null);
        if (!answer.ok()) {
            log.warn("project-draft: AI returned error: {}", answer.error());
            return buildFallback(requirement);
        }

        JsonNode json = aiService.parseJsonLoose(answer.text());
        if (json == null || !json.isObject()) {
            log.warn("project-draft: AI returned non-JSON response, using fallback");
            return buildFallback(requirement);
        }

        DraftResp resp = new DraftResp();
        resp.name = trimTo(json.path("name").asText(FALLBACK_FIELDS.get("name")), 50);
        resp.brand = trimTo(json.path("brand").asText(FALLBACK_FIELDS.get("brand")), 50);
        resp.category = sanitizeCategory(json.path("category").asText(FALLBACK_FIELDS.get("category")));
        resp.product = trimTo(json.path("product").asText(FALLBACK_FIELDS.get("product")), 80);
        resp.sellingPoints = trimTo(json.path("sellingPoints").asText(FALLBACK_FIELDS.get("sellingPoints")), 500);
        resp.audience = trimTo(json.path("audience").asText(FALLBACK_FIELDS.get("audience")), 150);
        resp.tone = sanitizeTone(json.path("tone").asText(FALLBACK_FIELDS.get("tone")));
        resp.bannedWords = trimTo(json.path("bannedWords").asText(FALLBACK_FIELDS.get("bannedWords")), 500);
        resp.extraPrompt = trimTo(json.path("extraPrompt").asText(FALLBACK_FIELDS.get("extraPrompt")), 500);

        JsonNode params = json.get("defaultParams");
        if (params != null && params.isObject()) {
            try {
                resp.defaultParams = objectMapper.writeValueAsString(params);
            } catch (Exception e) {
                resp.defaultParams = FALLBACK_DEFAULT_PARAMS;
            }
        } else {
            resp.defaultParams = FALLBACK_DEFAULT_PARAMS;
        }

        resp.aiGenerated = true;
        return resp;
    }

    private DraftResp buildFallback(String requirement) {
        DraftResp resp = new DraftResp();
        resp.name = requirement.isBlank()
                ? FALLBACK_FIELDS.get("name")
                : trimTo(requirement, 50);
        resp.brand = FALLBACK_FIELDS.get("brand");
        resp.category = FALLBACK_FIELDS.get("category");
        resp.product = FALLBACK_FIELDS.get("product");
        resp.sellingPoints = FALLBACK_FIELDS.get("sellingPoints");
        resp.audience = FALLBACK_FIELDS.get("audience");
        resp.tone = FALLBACK_FIELDS.get("tone");
        resp.bannedWords = FALLBACK_FIELDS.get("bannedWords");
        resp.extraPrompt = FALLBACK_FIELDS.get("extraPrompt");
        resp.defaultParams = FALLBACK_DEFAULT_PARAMS;
        resp.aiGenerated = false;
        return resp;
    }

    private static String trimTo(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "美妆", "护肤", "食品饮料", "母婴", "3C 数码", "家清日用", "服饰", "保健品");

    private static String sanitizeCategory(String value) {
        String trimmed = trimTo(value, 20);
        if (trimmed.isBlank() || !VALID_CATEGORIES.contains(trimmed)) return FALLBACK_FIELDS.get("category");
        return trimmed;
    }

    private static final Set<String> VALID_TONES = Set.of(
            "真诚测评", "闺蜜安利", "专业成分党", "搞笑玩梗", "高级质感", "急促带货");

    private static String sanitizeTone(String value) {
        String trimmed = trimTo(value, 20);
        if (trimmed.isBlank() || !VALID_TONES.contains(trimmed)) return FALLBACK_FIELDS.get("tone");
        return trimmed;
    }
}
