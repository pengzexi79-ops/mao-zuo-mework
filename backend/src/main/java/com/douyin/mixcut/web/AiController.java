package com.douyin.mixcut.web;

import com.douyin.mixcut.domain.*;
import com.douyin.mixcut.external.AiClient;
import com.douyin.mixcut.repository.Repositories.*;
import com.douyin.mixcut.security.CredentialCipher;
import com.douyin.mixcut.service.AiService;
import com.douyin.mixcut.service.CopyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AI 接入管理：供应商、用途路由、连通性测试、文案生成。 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiProviderRepo providerRepo;
    private final AiRouteRepo routeRepo;
    private final AiLogRepo logRepo;
    private final ProjectRepo projectRepo;
    private final AiService aiService;
    private final CopyService copyService;
    private final CredentialCipher credentialCipher;
    private final com.douyin.mixcut.service.MediaProviderCatalog mediaCatalog;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------------- 供应商 ----------------

    @GetMapping("/providers")
    public R<List<Map<String, Object>>> providers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiProvider p : providerRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("kind", p.getKind());
            m.put("baseUrl", p.getBaseUrl());
            String protectedKey = p.getApiKey();
            String visibleMask = credentialCipher.encrypted(protectedKey) && credentialCipher.available()
                    ? mask(credentialCipher.decrypt(protectedKey)) : mask(protectedKey);
            m.put("apiKeyMasked", visibleMask);
            m.put("hasKey", protectedKey != null && !protectedKey.isBlank());
            m.put("credentialsProtected", credentialCipher.encrypted(protectedKey));
            m.put("canSaveCredentials", credentialCipher.available());
            m.put("priority", p.getPriority());
            m.put("enabled", p.getEnabled());
            m.put("models", modelsView(p.getModels()));
            m.put("mediaCapabilities", mediaCatalog.read(p).view());
            addDiscoveryState(m, p);
            m.put("defaultModel", p.getDefaultModel());
            out.add(m);
        }
        return R.ok(out);
    }

    @PostMapping("/providers")
    public R<Map<String, Object>> create(@RequestBody AiProvider p) {
        if (p.getName() == null || p.getName().isBlank()) return R.fail("名称不能为空");
        if (p.getKind() == null) p.setKind(ProviderKind.openai);
        try {
            AiClient.validateProviderBaseUrl(p.getBaseUrl(), p.getKind());
        } catch (IllegalArgumentException e) {
            return R.fail("AI 服务 URL 格式错误或目标地址不允许访问: " + safeUrlError(e));
        }
        try {
            if (p.getMediaCapabilities() != null) p.setModels(mediaCatalog.mergeMediaConfig(p.getModels(), p.getMediaCapabilities()));
            if (p.getApiKey() != null && !p.getApiKey().isBlank()) p.setApiKey(credentialCipher.encrypt(p.getApiKey()));
            p.setId(null);
            AiProvider saved = providerRepo.save(p);
            discoverAndPersist(saved);
            return R.ok(providerView(providerRepo.findById(saved.getId()).orElse(saved)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @PutMapping("/providers/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody AiProvider in) {
        AiProvider p = providerRepo.findById(id).orElse(null);
        if (p == null) return R.fail("供应商不存在");
        ProviderKind requestedKind = in.getKind() == null ? p.getKind() : in.getKind();
        String requestedBaseUrl = in.getBaseUrl() == null ? p.getBaseUrl() : in.getBaseUrl();
        try {
            AiClient.validateProviderBaseUrl(requestedBaseUrl, requestedKind);
        } catch (IllegalArgumentException e) {
            return R.fail("AI 服务 URL 格式错误或目标地址不允许访问: " + safeUrlError(e));
        }
        if (in.getName() != null) p.setName(in.getName());
        if (in.getKind() != null) p.setKind(in.getKind());
        if (in.getBaseUrl() != null) p.setBaseUrl(in.getBaseUrl());
        // 前端回传掩码时不覆盖已保存的 key
        if (in.getApiKey() != null && !in.getApiKey().isBlank() && !in.getApiKey().contains("****")) {
            try {
                p.setApiKey(credentialCipher.encrypt(in.getApiKey()));
            } catch (IllegalStateException e) {
                return R.fail(e.getMessage());
            }
        }
        if (in.getPriority() != null) p.setPriority(in.getPriority());
        if (in.getEnabled() != null) p.setEnabled(in.getEnabled());
        if (in.getModels() != null) p.setModels(in.getModels());
        if (in.getMediaCapabilities() != null) {
            try {
                p.setModels(mediaCatalog.mergeMediaConfig(p.getModels(), in.getMediaCapabilities()));
            } catch (IllegalArgumentException e) {
                return R.fail("媒体模型能力配置错误: " + safeUrlError(e));
            }
        }
        if (in.getDefaultModel() != null) p.setDefaultModel(in.getDefaultModel());
        AiProvider saved = providerRepo.save(p);
        if (in.getApiKey() != null && !in.getApiKey().isBlank() && !in.getApiKey().contains("****")) {
            discoverAndPersist(saved);
            saved = providerRepo.findById(saved.getId()).orElse(saved);
        }
        return R.ok(providerView(saved));
    }

    @Data
    public static class AdoptMediaReq {
        private String capability;
        private List<String> models;
    }

    @PostMapping("/providers/{id}/adopt-media")
    public R<Map<String, Object>> adoptMedia(@PathVariable Long id, @RequestBody AdoptMediaReq req) {
        AiProvider provider = providerRepo.findById(id).orElse(null);
        if (provider == null) return R.fail("供应商不存在");
        if (req == null || req.getCapability() == null) return R.fail("缺少媒体能力类型");
        try {
            provider.setModels(mediaCatalog.adoptObservedMedia(provider.getModels(), req.getCapability(), req.getModels()));
            AiProvider saved = providerRepo.save(provider);
            return R.ok(providerView(saved));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @DeleteMapping("/providers/{id}")
    public R<Void> delete(@PathVariable Long id) {
        providerRepo.deleteById(id);
        return R.ok();
    }

    @GetMapping("/preset-models")
    public R<Map<String, List<String>>> presetModels() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (ProviderKind k : ProviderKind.values()) m.put(k.name(), AiClient.defaultModels(k));
        return R.ok(m);
    }

    @PostMapping("/providers/{id}/discover-models")
    public R<Map<String, Object>> discoverModels(@PathVariable Long id) {
        AiProvider provider = providerRepo.findById(id).orElse(null);
        if (provider == null) return R.fail("供应商不存在");
        AiClient.ModelDiscovery discovery = aiClient.discoverModels(provider);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", discovery.ok());
        result.put("models", discovery.models());
        result.put("textModels", discovery.textModels());
        result.put("imageModels", discovery.imageModels());
        result.put("videoModels", discovery.videoModels());
        result.put("voiceModels", discovery.voiceModels());
        result.put("visionModels", discovery.visionModels());
        result.put("latencyMs", discovery.latencyMs());
        result.put("discoveredAt", discovery.discoveredAt());
        result.put("provider", provider.getName());
        if (!discovery.ok()) {
            result.put("error", discovery.error());
            return R.fail(discovery.error());
        }
        try {
            provider.setModels(mergeDiscoveredModels(provider.getModels(), discovery));
            selectDefaultTextModel(provider, discovery.textModels());
            AiProvider saved = providerRepo.save(provider);
            result.put("providerView", providerView(saved));
            result.put("recommendations", recommendModels(discovery.textModels(), provider.getKind()));
            result.put("message", discovery.imageModels().isEmpty() && discovery.videoModels().isEmpty() && discovery.voiceModels().isEmpty()
                    ? "已同步最新文本模型；该 Provider 当前未返回可确认的图片、视频或配音模型"
                    : "已同步最新文本及媒体模型能力");
            return R.ok(result);
        } catch (Exception e) {
            return R.fail("模型已识别，但同步 Provider 配置失败");
        }
    }

    @Data
    public static class TestReq {
        private Long providerId;
        private String model;
    }

    @PostMapping("/test")
    public R<Map<String, Object>> test(@RequestBody TestReq req) {
        if (req.getProviderId() == null) return R.fail("缺少 providerId");
        AiService.Answer a = aiService.test(req.getProviderId(), req.getModel());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", a.ok());
        m.put("text", a.text());
        m.put("error", a.error());
        m.put("provider", a.providerName());
        m.put("model", a.model());
        return a.ok() ? R.ok(m) : R.fail(a.error());
    }

    // ---------------- 路由 ----------------

    @GetMapping("/routes")
    public R<List<AiRoute>> routes() {
        List<AiRoute> list = new ArrayList<>();
        for (UseCase u : UseCase.values()) {
            list.add(routeRepo.findByUseCase(u.name()).orElseGet(() -> {
                AiRoute r = new AiRoute();
                r.setUseCase(u.name());
                return r;
            }));
        }
        return R.ok(list);
    }

    @PutMapping("/routes/{useCase}")
    public R<AiRoute> saveRoute(@PathVariable String useCase, @RequestBody AiRoute in) {
        UseCase u;
        try {
            u = UseCase.valueOf(useCase);
        } catch (Exception e) {
            return R.fail("未知用途: " + useCase);
        }
        AiRoute r = routeRepo.findByUseCase(u.name()).orElseGet(AiRoute::new);
        r.setUseCase(u.name());
        r.setProviderId(in.getProviderId());
        r.setModel(in.getModel());
        r.setFallbacks(in.getFallbacks());
        return R.ok(routeRepo.save(r));
    }

    @GetMapping("/logs")
    public R<List<AiLog>> logs() {
        return R.ok(logRepo.findTop200ByOrderByIdDesc());
    }

    @GetMapping("/ready")
    public R<Boolean> ready() {
        return R.ok(aiService.ready());
    }

    // ---------------- 内置 AI 对话 ----------------

    @Data
    public static class ChatReq {
        private Long projectId;
        private List<ChatMessage> messages;
        private Integer maxTokens = 900;
    }

    @Data
    public static class ChatMessage {
        private String role;
        private String content;
    }

    @PostMapping("/chat")
    public R<Map<String, Object>> chat(@RequestBody ChatReq req) {
        if (req.getMessages() == null || req.getMessages().isEmpty()) return R.fail("请输入对话内容");
        if (req.getMessages().size() > 20) return R.fail("单次最多保留 20 条对话");
        StringBuilder user = new StringBuilder();
        for (ChatMessage message : req.getMessages()) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) continue;
            String role = "assistant".equalsIgnoreCase(message.getRole()) ? "助手" : "用户";
            String content = message.getContent().trim();
            if (content.length() > 4000) return R.fail("单条消息不能超过 4000 字");
            user.append(role).append(":\n").append(content).append("\n\n");
        }
        if (user.length() == 0) return R.fail("请输入对话内容");
        Project project = req.getProjectId() == null ? null : projectRepo.findById(req.getProjectId()).orElse(null);
                String context = project == null ? "" : buildProjectContext(project);
        AiService.Answer answer = aiService.ask(UseCase.chat,
                buildAssistantSystem(context),
                user.toString(), 0.75, Math.max(128, Math.min(1800, req.getMaxTokens() == null ? 900 : req.getMaxTokens())), project == null ? null : project.getRouteOverrides());
        if (!answer.ok()) return R.fail(answer.error());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", answer.text());
        out.put("provider", answer.providerName());
        out.put("model", answer.model());
        return R.ok(out);
    }

    private String buildProjectContext(Project project) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前项目：").append(nvl(project.getName()));
        sb.append("；品牌：").append(nvl(project.getBrand()));
        sb.append("；品类：").append(nvl(project.getCategory()));
        sb.append("；产品：").append(nvl(project.getProduct()));
        sb.append("；卖点：").append(nvl(project.getSellingPoints()));
        sb.append("；目标人群：").append(nvl(project.getAudience()));
        sb.append("；语气：").append(nvl(project.getTone()));
        sb.append("；禁用词：").append(nvl(project.getBannedWords()));
        return sb.toString();
    }

    private String nvl(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }

    private String buildAssistantSystem(String context) {
        return """
你是「喵作 · Mework」的本地短视频混剪智能助手，服务带货/种草视频的生产者（美妆、护肤、食品、日用、3C 等）。
你能围绕以下真实能力给出专业、可执行、开放的建议：
1. 素材管理：导入、角色分类（实拍主体/自家产品/钩子/背景音乐/人声口播）、自动切片、按项目过滤无关素材、批量删除、AI 主题审核；
2. 公开素材抓取：Mixkit/Freesound/Wikimedia/Internet Archive/Pixabay 等来源关键词检索与导入，按项目相关性评分与命中词展示；
3. 批量出片：时长 50-150s、切片节奏、跨条去重不重样、保留原片声音/AI 人声/BGM 轮换、持续生成、干跑预检、出片台参数说明；
4. 字幕：AI 口播用真实 ASR 时间轴生成并烧录字幕，原片素材也可识别字幕；
5. 交付质检：静音/黑屏/音画同步/字幕时间轴等六维审核，拦截时给出可读的具体原因；
6. 环境中心：Java/ffmpeg/MySQL/Python 依赖自检、一键处理、本机版本记录与发布历史。
回答要求：口语化、先给结论再给理由与可执行步骤；涉及参数时给具体数值建议；遇到不确定时说明并给出最稳妥的默认做法；可以主动追问素材、时长、卖点等信息来给出更准的方案。
边界：不要生成 shell 命令、不要绕过登录或下载受限内容、不要虚构本应用不存在的功能。
""" + context;
    }
    // ---------------- 文案 ----------------

    @Data
    public static class CopyReq {
        private Long projectId;
        private Integer count = 5;
        private Integer seconds = 60;
        private String extra;
    }

    @PostMapping("/copy/hooks")
    public R<List<String>> hooks(@RequestBody CopyReq req) {
        Project p = req.getProjectId() == null ? null : projectRepo.findById(req.getProjectId()).orElse(null);
        return R.ok(copyService.hooks(p, Math.max(1, Math.min(30, req.getCount())), req.getExtra()));
    }

    @PostMapping("/copy/script")
    public R<String> script(@RequestBody CopyReq req) {
        Project p = req.getProjectId() == null ? null : projectRepo.findById(req.getProjectId()).orElse(null);
        return R.ok(copyService.script(p, req.getSeconds() == null ? 60 : req.getSeconds(), req.getExtra()));
    }

    @PostMapping("/copy/titles")
    public R<List<String>> titles(@RequestBody CopyReq req) {
        Project p = req.getProjectId() == null ? null : projectRepo.findById(req.getProjectId()).orElse(null);
        return R.ok(copyService.titles(p, Math.max(1, Math.min(30, req.getCount()))));
    }

    private void discoverAndPersist(AiProvider provider) {
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isBlank()) return;
        AiClient.ModelDiscovery discovery = aiClient.discoverModels(provider);
        try {
            if (discovery.ok()) {
                provider.setModels(mergeDiscoveredModels(provider.getModels(), discovery));
                selectDefaultTextModel(provider, discovery.textModels());
            } else {
                provider.setModels(markDiscoveryFailure(provider.getModels(), discovery.error()));
            }
            providerRepo.save(provider);
        } catch (Exception ignored) {
            // Discovery is advisory and must never make a valid provider save fail.
        }
    }

    private void selectDefaultTextModel(AiProvider provider, List<String> models) {
        if (provider == null || models == null || models.isEmpty()) return;
        String strongest = models.stream()
                .filter(model -> model != null && !model.isBlank())
                .max(Comparator.comparingInt(this::modelStrengthScore)
                        .thenComparing(model -> model, String.CASE_INSENSITIVE_ORDER))
                .orElse("");
        if (!strongest.isBlank()) provider.setDefaultModel(strongest);
    }

    private int modelStrengthScore(String model) {
        String id = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        if (id.matches(".*(gpt-5|gpt-4|o1|o3|o4|opus|sonnet|pro|max|reason|reasoning|deepseek-r1|qwen-max|glm-4-plus).*")) score += 100;
        if (id.matches(".*(mini|flash|haiku|small|lite|turbo|instant|nano|micro|free).*")) score -= 30;
        java.util.regex.Matcher size = java.util.regex.Pattern.compile("\\b([0-9]{1,3})b\\b").matcher(id);
        if (size.find()) score += Integer.parseInt(size.group(1));
        return score;
    }

    private void addDiscoveryState(Map<String, Object> view, AiProvider provider) {
        try {
            JsonNode root = provider.getModels() == null ? null : objectMapper.readTree(provider.getModels());
            view.put("discoveryStatus", root == null ? "never" : root.path("discoveryStatus").asText("never"));
            view.put("discoveredAt", root == null ? 0 : root.path("discoveredAt").asLong(0));
            view.put("discoveryLatencyMs", root == null ? 0 : root.path("discoveryLatencyMs").asLong(0));
            view.put("discoveryError", root == null ? "" : root.path("discoveryError").asText(""));
        } catch (Exception ignored) {
            view.put("discoveryStatus", "invalid");
            view.put("discoveredAt", 0);
            view.put("discoveryError", "Provider 模型配置格式无法解析");
        }
    }

    private String markDiscoveryFailure(String existing, String error) {
        try {
            JsonNode root = existing == null || existing.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(existing);
            com.fasterxml.jackson.databind.node.ObjectNode normalized = root != null && root.isObject()
                    ? ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy() : objectMapper.createObjectNode();
            normalized.put("discoveryStatus", "failed");
            normalized.put("discoveryError", error == null ? "模型探测失败" : error);
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ignored) {
            return existing == null || existing.isBlank() ? "{\"discoveryStatus\":\"failed\"}" : existing;
        }
    }

    private String mergeDiscoveredModels(String existing, AiClient.ModelDiscovery discovery) {
        try {
            JsonNode root = existing == null || existing.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(existing);
            com.fasterxml.jackson.databind.node.ObjectNode normalized = root != null && root.isObject()
                    ? ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy()
                    : objectMapper.createObjectNode();
            List<String> configuredText = new ArrayList<>();
            JsonNode existingText = normalized.path("text");
            if (existingText.isArray()) {
                for (JsonNode value : existingText) {
                    String model = value.asText("").trim();
                    if (!model.isBlank() && !configuredText.contains(model)) configuredText.add(model);
                }
            } else if (root != null && root.isArray()) {
                for (JsonNode value : root) {
                    String model = value.asText("").trim();
                    if (!model.isBlank() && !configuredText.contains(model)) configuredText.add(model);
                }
            }
            for (String model : discovery.textModels()) if (!configuredText.contains(model)) configuredText.add(model);
            normalized.set("text", objectMapper.valueToTree(configuredText));
            normalized.set("vision", objectMapper.valueToTree(discovery.visionModels()));
            com.fasterxml.jackson.databind.node.ObjectNode media = normalized.with("media");
            media.set("vision", objectMapper.valueToTree(discovery.visionModels()));
            // Discovery is advisory. Keep paid media execution allowlists under media unchanged.
            // Users must explicitly confirm image/video/voice models through the provider editor.
            com.fasterxml.jackson.databind.node.ObjectNode observed = normalized.putObject("observed");
            observed.set("text", objectMapper.valueToTree(discovery.textModels()));
            observed.set("image", objectMapper.valueToTree(discovery.imageModels()));
            observed.set("video", objectMapper.valueToTree(discovery.videoModels()));
            observed.set("voice", objectMapper.valueToTree(discovery.voiceModels()));
            observed.set("vision", objectMapper.valueToTree(discovery.visionModels()));
            normalized.put("discoveryStatus", "success");
            normalized.put("discoveredAt", discovery.discoveredAt());
            normalized.put("discoveryLatencyMs", discovery.latencyMs());
            normalized.remove("discoveryError");
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "text", discovery.textModels(),
                        "media", Map.of("image", discovery.imageModels(), "video", discovery.videoModels(), "voice", discovery.voiceModels(), "vision", discovery.visionModels()),
                        "vision", discovery.visionModels(),
                        "discoveryStatus", "success",
                        "discoveredAt", discovery.discoveredAt()));
            } catch (Exception ignored) { return "{\"text\":[],\"media\":{\"image\":[],\"video\":[],\"voice\":[]}}"; }
        }
    }

    private Map<String, List<String>> recommendModels(List<String> models, ProviderKind kind) {
        Map<String, List<String>> recommendations = new LinkedHashMap<>();
        recommendations.put("通用对话 / 项目助手", pickMatches(models, "gpt", "deepseek", "doubao", "qwen", "claude", "gemini"));
        recommendations.put("素材标签 / 轻量理解", pickMatches(models, "mini", "flash", "lite", "glm-4-flash", "qwen-turbo"));
        recommendations.put("脚本 / 分镜 / 营销计划", pickMatches(models, "gpt-4o", "deepseek-reasoner", "doubao-seed", "qwen-max", "claude-sonnet", "gemini-.*pro"));
        recommendations.put("视觉 / 视频理解（仅候选）", pickMatches(models, "vl", "vision", "seed", "gemini-.*pro", "gpt-4o"));
        return recommendations;
    }

    private List<String> pickMatches(List<String> models, String... tokens) {
        List<String> matches = new ArrayList<>();
        for (String model : models) {
            String lower = model.toLowerCase(java.util.Locale.ROOT);
            for (String token : tokens) {
                if (lower.matches(".*" + token.toLowerCase(java.util.Locale.ROOT) + ".*")) {
                    matches.add(model);
                    break;
                }
            }
            if (matches.size() >= 8) break;
        }
        return matches;
    }

    private Map<String, Object> providerView(AiProvider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("kind", p.getKind());
        m.put("baseUrl", p.getBaseUrl());
        m.put("hasKey", p.getApiKey() != null && !p.getApiKey().isBlank());
        m.put("apiKeyMasked", credentialCipher.encrypted(p.getApiKey()) && credentialCipher.available()
                ? mask(credentialCipher.decrypt(p.getApiKey())) : mask(p.getApiKey()));
        m.put("credentialsProtected", credentialCipher.encrypted(p.getApiKey()));
        m.put("priority", p.getPriority());
        m.put("enabled", p.getEnabled());
        m.put("models", modelsView(p.getModels()));
        m.put("mediaCapabilities", mediaCatalog.read(p).view());
        m.put("defaultModel", p.getDefaultModel());
        return m;
    }

    private String safeUrlError(IllegalArgumentException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "无效 URL" : message;
    }

    /**
     * Older providers may have been discovered before GPT vision heuristics existed.
     * Keep their stored JSON intact, but expose deterministic GPT vision candidates to
     * the settings UI and downstream model pickers immediately.
     */
    private String modelsView(String raw) {
        try {
            JsonNode root = raw == null || raw.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
            com.fasterxml.jackson.databind.node.ObjectNode normalized = root != null && root.isObject()
                    ? ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy()
                    : objectMapper.createObjectNode();
            JsonNode text = normalized.path("text");
            if (!text.isArray() && root != null && root.isArray()) {
                text = root;
                normalized.set("text", root);
            }
            List<String> vision = new ArrayList<>();
            JsonNode existing = normalized.path("vision");
            if (existing.isArray()) for (JsonNode value : existing) if (!value.asText("").isBlank()) vision.add(value.asText());
            if (text != null && text.isArray()) {
                for (JsonNode value : text) {
                    String model = value.asText("").trim();
                    if (AiClient.looksVisionCapableGpt(model) && !vision.contains(model)) vision.add(model);
                }
            }
            normalized.set("vision", objectMapper.valueToTree(vision));
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
