package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.security.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads only explicit media capabilities saved with a provider. No model is assumed from its name. */
@Service
@RequiredArgsConstructor
public class MediaProviderCatalog {
    private final ObjectMapper om;

    public record ModelRoute(String protocol, String endpoint, String confidence, String source, Long verifiedAt) {
        public ModelRoute {
            protocol = protocol == null ? "" : protocol;
            endpoint = endpoint == null ? "" : endpoint;
            confidence = confidence == null || confidence.isBlank() ? "medium" : confidence;
            source = source == null || source.isBlank() ? "automatic" : source;
        }
    }

    public record Capability(List<String> imageModels, List<String> videoModels, List<String> voiceModels,
                             List<String> visionModels, String imageEndpoint, String videoEndpoint,
                             String voiceEndpoint, String imageProtocol, String videoProtocol,
                             String voiceProtocol, String setupUrl, String billingUrl,
                             Map<String, Map<String, ModelRoute>> modelRoutes) {
        public Capability(List<String> imageModels, List<String> videoModels, List<String> voiceModels,
                          List<String> visionModels, String imageEndpoint, String videoEndpoint,
                          String voiceEndpoint, String imageProtocol, String videoProtocol,
                          String voiceProtocol, String setupUrl, String billingUrl) {
            this(imageModels, videoModels, voiceModels, visionModels, imageEndpoint, videoEndpoint, voiceEndpoint,
                    imageProtocol, videoProtocol, voiceProtocol, setupUrl, billingUrl, Map.of());
        }
        /** Keeps source compatibility for callers written before image/video endpoints existed. */
        public Capability(List<String> imageModels, List<String> videoModels, List<String> voiceModels,
                          List<String> visionModels, String voiceEndpoint, String imageProtocol,
                          String videoProtocol, String voiceProtocol, String setupUrl, String billingUrl) {
            this(imageModels, videoModels, voiceModels, visionModels, "", "", voiceEndpoint,
                    imageProtocol, videoProtocol, voiceProtocol, setupUrl, billingUrl);
        }
        public String protocol(String operation) {
            return switch (operation) {
                case "image" -> imageProtocol;
                case "video" -> videoProtocol;
                case "voice" -> voiceProtocol;
                default -> "";
            };
        }
        public String protocol(String operation, String model) { return route(operation, model).protocol(); }
        public String endpoint(String operation) {
            return switch (operation) {
                case "image" -> imageEndpoint;
                case "video" -> videoEndpoint;
                case "voice" -> voiceEndpoint;
                default -> "";
            };
        }
        public String endpoint(String operation, String model) { return route(operation, model).endpoint(); }
        public ModelRoute route(String operation, String model) {
            ModelRoute route = modelRoutes.getOrDefault(operation, Map.of()).get(model == null ? "" : model);
            return route != null ? route : new ModelRoute(protocol(operation), endpoint(operation), "low", "provider-default", null);
        }
        public static Capability empty() {
            return new Capability(List.of(), List.of(), List.of(), List.of(), "", "", "", "", "", "", "", "", Map.of());
        }

        public boolean supports(String operation, String model) {
            return models(operation).contains(model);
        }

        public List<String> models(String operation) {
            return switch (operation) {
                case "image" -> imageModels;
                case "video" -> videoModels;
                case "voice" -> voiceModels;
                default -> List.of();
            };
        }

        public boolean hasAny() {
            return !imageModels.isEmpty() || !videoModels.isEmpty() || !voiceModels.isEmpty();
        }

        public Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("imageModels", imageModels);
            value.put("videoModels", videoModels);
            value.put("voiceModels", voiceModels);
            value.put("visionModels", visionModels);
            value.put("imageGenerationModels", imageModels);
            value.put("imageEndpoint", imageEndpoint);
            value.put("videoEndpoint", videoEndpoint);
            value.put("voiceEndpoint", voiceEndpoint);
            value.put("imageProtocol", imageProtocol);
            value.put("videoProtocol", videoProtocol);
            value.put("voiceProtocol", voiceProtocol);
            value.put("modelRoutes", modelRoutes);
            value.put("setupUrl", setupUrl);
            value.put("billingUrl", billingUrl);
            return value;
        }
    }

    public Capability read(AiProvider provider) {
        try {
            JsonNode root = om.readTree(provider.getModels() == null ? "{}" : provider.getModels());
            JsonNode media = root != null && root.isObject() ? root.path("media") : null;
            if (media == null || !media.isObject()) {
                return withOfficialLinks(provider, Capability.empty());
            }
            String setup = safeExternalUrl(media.path("setupUrl").asText(""));
            String billing = safeExternalUrl(media.path("billingUrl").asText(""));
            List<String> imageModels = models(media.path("image"));
            List<String> videoModels = models(media.path("video"));
            List<String> voiceModels = models(media.path("voice"));
            String imageProtocol = safeProtocol(media.path("imageProtocol").asText(""), defaultProtocol("image", imageModels));
            String imageEndpoint = safeEndpoint(media.path("imageEndpoint").asText(""));
            String voiceProtocol = safeProtocol(media.path("voiceProtocol").asText(""), defaultProtocol("voice", voiceModels));
            String voiceEndpoint = safeEndpoint(media.path("voiceEndpoint").asText(""));
            String videoProtocol = safeProtocol(media.path("videoProtocol").asText(""), defaultProtocol("video", videoModels));
            String videoEndpoint = safeEndpoint(media.path("videoEndpoint").asText(""));
            Map<String, Map<String, ModelRoute>> routes = buildRoutes(provider, imageModels, videoModels, voiceModels,
                    readRoutes(media.path("routes")), imageProtocol, imageEndpoint, videoProtocol, videoEndpoint,
                    voiceProtocol, voiceEndpoint);
            Capability existing = new Capability(imageModels, videoModels, voiceModels, models(media.path("vision")),
                    imageEndpoint, videoEndpoint, voiceEndpoint, imageProtocol, videoProtocol, voiceProtocol,
                    setup.isBlank() ? officialSetup(provider) : setup,
                    billing.isBlank() ? officialBilling(provider) : billing, routes);
            return withOfficialLinks(provider, existing);
        } catch (Exception ignored) {
            return withOfficialLinks(provider, Capability.empty());
        }
    }

    public static boolean officialOpenAi(AiProvider provider) {
        if (provider == null) return false;
        if (provider.getKind() != null && provider.getKind() != com.douyin.mixcut.domain.ProviderKind.openai) return false;
        String base = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().trim().replaceAll("/+$", "");
        return "https://api.openai.com".equalsIgnoreCase(base);
    }

    public static boolean officialDashScope(AiProvider provider) {
        String host = providerHost(provider);
        return !host.isBlank() && host.matches("dashscope(?:-[a-z0-9-]+)?\\.aliyuncs\\.com");
    }

    public static boolean officialAlibabaWorkspace(AiProvider provider) {
        String host = providerHost(provider);
        return host.startsWith("ws-") && host.endsWith(".maas.aliyuncs.com");
    }

    private static String providerHost(AiProvider provider) {
        if (provider == null || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) return "";
        try {
            String host = URI.create(provider.getBaseUrl().trim()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public Map<String, Map<String, ModelRoute>> suggestRoutes(AiProvider provider, List<String> imageModels,
                                                               List<String> videoModels, List<String> voiceModels) {
        Capability current = read(provider);
        return buildRoutes(provider, imageModels, videoModels, voiceModels, current.modelRoutes(),
                current.imageProtocol(), current.imageEndpoint(), current.videoProtocol(), current.videoEndpoint(),
                current.voiceProtocol(), current.voiceEndpoint());
    }

    /** Adopts only models observed from this provider; other media allowlists remain unchanged. */
    public String adoptObservedMedia(String existingModels, String capability, List<String> selected) {
        if (!List.of("image", "video", "voice").contains(capability)) throw new IllegalArgumentException("不支持的媒体能力类型");
        try {
            JsonNode root = om.readTree(existingModels == null || existingModels.isBlank() ? "{}" : existingModels);
            var normalized = root != null && root.isObject() ? ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy() : om.createObjectNode();
            JsonNode observed = normalized.path("observed").path(capability);
            List<String> allowed = models(observed);
            List<String> chosen = models(om.valueToTree(selected == null ? List.of() : selected));
            if (!allowed.containsAll(chosen)) throw new IllegalArgumentException("只能采用最近一次识别到的候选模型");
            var media = normalized.with("media");
            media.set(capability, om.valueToTree(chosen));
            return om.writeValueAsString(normalized);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("模型候选配置格式无效"); }
    }

    /** Merges media capability settings into the legacy models JSON without changing text-model entries. */
    public String mergeMediaConfig(String existingModels, String raw) {
        try {
            JsonNode input = om.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            if (input == null || !input.isObject()) throw new IllegalArgumentException("模型能力配置必须是 JSON 对象");
            var root = om.readTree(existingModels == null || existingModels.isBlank() ? "{}" : existingModels);
            var normalized = root != null && root.isObject() ? ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy() : om.createObjectNode();
            if (root != null && root.isArray()) normalized.set("text", root);
            var media = normalized.putObject("media");
            List<String> imageModels = models(input.path("image"));
            List<String> videoModels = models(input.path("video"));
            List<String> voiceModels = models(input.path("voice"));
            media.set("image", om.valueToTree(imageModels));
            media.set("video", om.valueToTree(videoModels));
            media.set("voice", om.valueToTree(voiceModels));
            media.set("vision", om.valueToTree(models(input.path("vision"))));
            String imageEndpoint = safeEndpoint(input.path("imageEndpoint").asText(""));
            String videoEndpoint = safeEndpoint(input.path("videoEndpoint").asText(""));
            String imageProtocol = safeProtocol(input.path("imageProtocol").asText(""), defaultProtocol("image", imageModels));
            String videoProtocol = safeProtocol(input.path("videoProtocol").asText(""), defaultProtocol("video", videoModels));
            String voiceEndpoint = safeEndpoint(input.path("voiceEndpoint").asText(""));
            String voiceProtocol = safeProtocol(input.path("voiceProtocol").asText(""), defaultProtocol("voice", voiceModels));
            if (!imageEndpoint.isBlank()) media.put("imageEndpoint", imageEndpoint);
            if (!videoEndpoint.isBlank()) media.put("videoEndpoint", videoEndpoint);
            if (!voiceEndpoint.isBlank()) media.put("voiceEndpoint", voiceEndpoint);
            if (!imageProtocol.isBlank()) media.put("imageProtocol", imageProtocol);
            if (!videoProtocol.isBlank()) media.put("videoProtocol", videoProtocol);
            if (!voiceProtocol.isBlank()) media.put("voiceProtocol", voiceProtocol);
            Map<String, Map<String, ModelRoute>> routes = readRoutes(input.path("routes"));
            if (!routes.isEmpty()) media.set("routes", om.valueToTree(routes));
            String setup = safeExternalUrl(input.path("setupUrl").asText(""));
            String billing = safeExternalUrl(input.path("billingUrl").asText(""));
            if (!setup.isBlank()) media.put("setupUrl", setup);
            if (!billing.isBlank()) media.put("billingUrl", billing);
            return om.writeValueAsString(normalized);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("模型能力配置格式无效");
        }
    }

    private Capability withOfficialLinks(AiProvider provider, Capability existing) {
        String setup = existing.setupUrl() == null || existing.setupUrl().isBlank() ? officialSetup(provider) : existing.setupUrl();
        String billing = existing.billingUrl() == null || existing.billingUrl().isBlank() ? officialBilling(provider) : existing.billingUrl();
        return new Capability(existing.imageModels(), existing.videoModels(), existing.voiceModels(), existing.visionModels(),
                existing.imageEndpoint(), existing.videoEndpoint(), existing.voiceEndpoint(), existing.imageProtocol(),
                existing.videoProtocol(), existing.voiceProtocol(), setup, billing, existing.modelRoutes());
    }

    private String safeEndpoint(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim();
        if (value.startsWith("/")) {
            if (value.contains("..") || value.contains("?") || value.contains("#") || !value.matches("/[A-Za-z0-9._~:/-]+")) {
                throw new IllegalArgumentException("媒体 endpoint 路径无效");
            }
            return value;
        }
        String url = UrlGuard.validate(value);
        if (!url.startsWith("https://")) throw new IllegalArgumentException("媒体 endpoint 必须使用 HTTPS");
        return url;
    }

    private String safeProtocol(String raw, String defaultValue) {
        String value = raw == null || raw.isBlank() ? defaultValue : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank()) return "";
        if (value.length() > 80 || !value.matches("[a-z0-9][a-z0-9._-]*")) throw new IllegalArgumentException("媒体协议标识无效");
        return value;
    }

    private String defaultProtocol(String operation, List<String> models) {
        if (models == null || models.isEmpty()) return "";
        return switch (operation) {
            case "image" -> "openai_image_generation";
            case "video" -> "openai_video_generation";
            case "voice" -> "openai_audio_speech";
            default -> "";
        };
    }

    private boolean isDashScope(AiProvider provider) {
        return officialDashScope(provider) || officialAlibabaWorkspace(provider);
    }

    public boolean supportsInferredRoute(AiProvider provider, String operation, String model) {
        return isExecutionEligible(operation, model)
                && !inferredRoute(provider, operation, model, "", "").protocol().isBlank();
    }

    public boolean isExecutionEligible(String operation, String model) {
        if (!"voice".equals(operation)) return true;
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return !lower.isBlank()
                && !lower.contains("realtime")
                && !lower.contains("voice-clone")
                && !lower.contains("voice_clone")
                && !lower.contains("voice-design")
                && !lower.contains("voice_design")
                && !lower.startsWith("qwen3-tts-vd-")
                && !lower.startsWith("qwen3-tts-vc-");
    }

    private boolean isDashScopeSyncImage(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("qwen-image-(?:3(?:[.-].*)?|2\\.0(?:[.-].*)?)")
                || (lower.startsWith("wan") && lower.contains("image") && !lower.contains("edit"));
    }

    private boolean isDashScopeAsyncImage(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("qwen-image-(?:plus|max)(?:[.-].*)?");
    }

    private boolean isDashScopeImageEdit(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("image-edit") || (lower.startsWith("wan") && lower.contains("image") && lower.contains("edit"));
    }

    private boolean isQwenTts(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("qwen3-tts") || lower.contains("qwen-tts");
    }

    private boolean isMiniMaxTts(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return (lower.contains("minimax")
                && (lower.contains("speech") || lower.contains("tts") || lower.contains("voice")))
                || lower.matches(".*(^|[/_:.-])speech-(?:0?[12]|2(?:\\.\\d+)?)(?:[_.-]|$).*");
    }

    private boolean isWanVideo(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("wan") || lower.contains("/wan") || lower.contains("wan-video");
    }

    private String dashScopeTtsEndpoint(AiProvider provider) {
        return dashScopeEndpoint(provider, "/api/v1/services/aigc/multimodal-generation/generation");
    }

    private String dashScopeImageEndpoint(AiProvider provider) {
        return dashScopeEndpoint(provider, "/api/v1/services/aigc/multimodal-generation/generation");
    }

    private String dashScopeAsyncImageEndpoint(AiProvider provider) {
        return dashScopeEndpoint(provider, "/api/v1/services/aigc/text2image/image-synthesis");
    }

    private String dashScopeVideoEndpoint(AiProvider provider) {
        return dashScopeEndpoint(provider, "/api/v1/services/aigc/video-generation/video-synthesis");
    }

    private String dashScopeEndpoint(AiProvider provider, String path) {
        if (!isDashScope(provider)) return "";
        URI base = URI.create(provider.getBaseUrl().trim());
        return base.getScheme() + "://" + base.getRawAuthority() + path;
    }

    private Map<String, Map<String, ModelRoute>> buildRoutes(AiProvider provider, List<String> imageModels,
                                                              List<String> videoModels, List<String> voiceModels,
                                                              Map<String, Map<String, ModelRoute>> explicit,
                                                              String imageProtocol, String imageEndpoint,
                                                              String videoProtocol, String videoEndpoint,
                                                              String voiceProtocol, String voiceEndpoint) {
        Map<String, Map<String, ModelRoute>> result = new LinkedHashMap<>();
        addRoutes(result, "image", imageModels, explicit, provider, imageProtocol, imageEndpoint);
        addRoutes(result, "video", videoModels, explicit, provider, videoProtocol, videoEndpoint);
        addRoutes(result, "voice", voiceModels, explicit, provider, voiceProtocol, voiceEndpoint);
        return immutableRoutes(result);
    }

    private void addRoutes(Map<String, Map<String, ModelRoute>> result, String operation, List<String> models,
                           Map<String, Map<String, ModelRoute>> explicit, AiProvider provider,
                           String providerProtocol, String providerEndpoint) {
        Map<String, ModelRoute> routes = new LinkedHashMap<>();
        for (String model : models == null ? List.<String>of() : models) {
            ModelRoute saved = explicit.getOrDefault(operation, Map.of()).get(model);
            routes.put(model, keepSavedRoute(saved)
                    ? saved
                    : inferredRoute(provider, operation, model, providerProtocol, providerEndpoint));
        }
        if (!routes.isEmpty()) result.put(operation, routes);
    }

    private boolean keepSavedRoute(ModelRoute route) {
        if (route == null) return false;
        String source = route.source().toLowerCase(java.util.Locale.ROOT);
        return route.verifiedAt() != null || List.of("saved", "manual", "user", "verified").contains(source);
    }

    private ModelRoute inferredRoute(AiProvider provider, String operation, String model,
                                     String providerProtocol, String providerEndpoint) {
        if (isDashScope(provider)) {
            if ("image".equals(operation) && isDashScopeImageEdit(model)) {
                return new ModelRoute("dashscope_image_edit_http", dashScopeImageEndpoint(provider), "high", "official-edit-only", null);
            }
            if ("image".equals(operation) && isDashScopeSyncImage(model)) {
                return new ModelRoute("dashscope_image_http", dashScopeImageEndpoint(provider), "high", "official-model-family", null);
            }
            if ("image".equals(operation) && isDashScopeAsyncImage(model)) {
                return new ModelRoute("dashscope_image_task_http", dashScopeAsyncImageEndpoint(provider), "high", "official-model-family", null);
            }
            if ("voice".equals(operation) && isMiniMaxTts(model)) {
                return new ModelRoute("dashscope_minimax_tts_http", dashScopeTtsEndpoint(provider), "high", "official-model-family", null);
            }
            if ("voice".equals(operation) && isQwenTts(model)) {
                return new ModelRoute("dashscope_tts_http", dashScopeTtsEndpoint(provider), "high", "official-model-family", null);
            }
            if ("video".equals(operation) && isWanVideo(model)) {
                return new ModelRoute("dashscope_video_task_http", dashScopeVideoEndpoint(provider), "high", "official-model-family", null);
            }
        }
        if (providerProtocol != null && !providerProtocol.isBlank()) {
            return new ModelRoute(providerProtocol, providerEndpoint, "medium", "provider-default", null);
        }
        return new ModelRoute(defaultProtocol(operation, List.of(model)), "", "medium", "openai-compatible-default", null);
    }

    private Map<String, Map<String, ModelRoute>> readRoutes(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        Map<String, Map<String, ModelRoute>> result = new LinkedHashMap<>();
        for (String operation : List.of("image", "video", "voice")) {
            JsonNode operationNode = node.path(operation);
            if (!operationNode.isObject()) continue;
            Map<String, ModelRoute> routes = new LinkedHashMap<>();
            operationNode.fields().forEachRemaining(entry -> {
                String model = entry.getKey().trim();
                JsonNode value = entry.getValue();
                if (!model.matches("[A-Za-z0-9._:/-]{1,120}") || !value.isObject()) return;
                String protocol = safeProtocol(value.path("protocol").asText(""), "");
                if (protocol.isBlank()) return;
                String endpoint = safeEndpoint(value.path("endpoint").asText(""));
                String confidence = value.path("confidence").asText("medium").trim().toLowerCase(java.util.Locale.ROOT);
                if (!List.of("low", "medium", "high").contains(confidence)) confidence = "medium";
                String source = value.path("source").asText("saved").replaceAll("[^A-Za-z0-9._-]", "");
                if (source.isBlank()) source = "saved";
                Long verifiedAt = value.path("verifiedAt").canConvertToLong() && value.path("verifiedAt").asLong() > 0
                        ? value.path("verifiedAt").asLong() : null;
                routes.put(model, new ModelRoute(protocol, endpoint, confidence, source, verifiedAt));
            });
            if (!routes.isEmpty()) result.put(operation, routes);
        }
        return immutableRoutes(result);
    }

    private Map<String, Map<String, ModelRoute>> immutableRoutes(Map<String, Map<String, ModelRoute>> routes) {
        Map<String, Map<String, ModelRoute>> result = new LinkedHashMap<>();
        routes.forEach((operation, values) -> result.put(operation, Map.copyOf(values)));
        return Map.copyOf(result);
    }

    private List<String> models(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String model = value.asText("").trim();
            if (!model.isBlank() && model.length() <= 120 && model.matches("[A-Za-z0-9._:/-]+") && !result.contains(model)) result.add(model);
            if (result.size() >= 30) break;
        }
        return List.copyOf(result);
    }

    private String safeExternalUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String url = UrlGuard.validate(raw);
        if (!url.startsWith("https://")) throw new IllegalArgumentException("官方链接必须使用 HTTPS");
        return url;
    }

    private String officialSetup(AiProvider provider) {
        String base = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().replaceAll("/+$", "");
        String lower = base.toLowerCase(java.util.Locale.ROOT);
        if ("https://api.openai.com".equalsIgnoreCase(base)) return "https://platform.openai.com/api-keys";
        if (lower.contains("volces.com")) return "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey";
        if (lower.contains("dashscope.aliyuncs.com")) return "https://dashscope.console.aliyun.com/apiKey";
        return "";
    }

    private String officialBilling(AiProvider provider) {
        String base = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().replaceAll("/+$", "");
        String lower = base.toLowerCase(java.util.Locale.ROOT);
        if ("https://api.openai.com".equalsIgnoreCase(base)) return "https://platform.openai.com/settings/organization/billing/overview";
        if (lower.contains("volces.com")) return "https://console.volcengine.com/finance/expense";
        if (lower.contains("dashscope.aliyuncs.com")) return "https://usercenter.console.aliyun.com/#/manage-account/payment";
        return "";
    }
}
