package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.security.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads only explicit media capabilities saved with a provider. No model is assumed from its name. */
@Service
@RequiredArgsConstructor
public class MediaProviderCatalog {
    private final ObjectMapper om;

    public record Capability(List<String> imageModels, List<String> videoModels, List<String> voiceModels,
                             List<String> visionModels, String imageEndpoint, String videoEndpoint,
                             String voiceEndpoint, String imageProtocol, String videoProtocol,
                             String voiceProtocol, String setupUrl, String billingUrl) {
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
        public static Capability empty() {
            return new Capability(List.of(), List.of(), List.of(), List.of(), "", "", "", "", "", "", "", "");
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
            String voiceProtocol = resolveVoiceProtocol(provider, media, voiceModels);
            String voiceEndpoint = safeEndpoint(media.path("voiceEndpoint").asText(""));
            if (voiceEndpoint.isBlank() && "dashscope_tts_http".equals(voiceProtocol)) {
                voiceEndpoint = dashScopeTtsEndpoint(provider);
            }
            Capability existing = new Capability(imageModels, videoModels, voiceModels, models(media.path("vision")),
                    safeEndpoint(media.path("imageEndpoint").asText("")), safeEndpoint(media.path("videoEndpoint").asText("")),
                    voiceEndpoint,
                    safeProtocol(media.path("imageProtocol").asText(""), defaultProtocol("image", imageModels)),
                    safeProtocol(media.path("videoProtocol").asText(""), defaultProtocol("video", videoModels)),
                    voiceProtocol,
                    setup.isBlank() ? officialSetup(provider) : setup,
                    billing.isBlank() ? officialBilling(provider) : billing);
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
                existing.videoProtocol(), existing.voiceProtocol(), setup, billing);
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

    private String resolveVoiceProtocol(AiProvider provider, JsonNode media, List<String> models) {
        String raw = media.path("voiceProtocol").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        String inferred = defaultProtocol("voice", models);
        // DashScope exposes Qwen TTS through its multimodal HTTP contract, not
        // /v1/audio/speech. Correct old auto-detected configs at read time so
        // users do not have to edit an internal protocol field manually.
        if (isDashScope(provider) && models.stream().anyMatch(this::isQwenTts)
                && (raw.isBlank() || "openai_audio_speech".equals(raw))) {
            return "dashscope_tts_http";
        }
        return safeProtocol(raw, inferred);
    }

    private boolean isDashScope(AiProvider provider) {
        return provider != null && provider.getBaseUrl() != null
                && provider.getBaseUrl().toLowerCase(java.util.Locale.ROOT).contains("dashscope.aliyuncs.com");
    }

    private boolean isQwenTts(String model) {
        String lower = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("qwen3-tts") || lower.contains("qwen-tts");
    }

    private String dashScopeTtsEndpoint(AiProvider provider) {
        return isDashScope(provider)
                ? "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
                : "";
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
