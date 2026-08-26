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
    private static final Capability OPENAI_OFFICIAL_DEFAULTS = new Capability(
            List.of("gpt-image-1", "gpt-image-1-mini"),
            List.of("sora-2", "sora-2-pro"),
            List.of("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
            List.of(), "", "openai_image_generation", "openai_video_generation", "openai_audio_speech",
            "https://platform.openai.com/api-keys",
            "https://platform.openai.com/settings/organization/billing/overview"
    );

    private final ObjectMapper om;

    public record Capability(List<String> imageModels, List<String> videoModels, List<String> voiceModels,
                             List<String> visionModels, String voiceEndpoint, String imageProtocol,
                             String videoProtocol, String voiceProtocol, String setupUrl, String billingUrl) {
        public String protocol(String operation) {
            return switch (operation) {
                case "image" -> imageProtocol;
                case "video" -> videoProtocol;
                case "voice" -> voiceProtocol;
                default -> "";
            };
        }
        public static Capability empty() {
            return new Capability(List.of(), List.of(), List.of(), List.of(), "", "", "", "openai_audio_speech", "", "");
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
                return officialOpenAi(provider) ? mergeOfficialDefaults(provider, Capability.empty())
                        : new Capability(List.of(), List.of(), List.of(), List.of(), "", "", "", "openai_audio_speech", officialSetup(provider), officialBilling(provider));
            }
            String setup = safeExternalUrl(media.path("setupUrl").asText(""));
            String billing = safeExternalUrl(media.path("billingUrl").asText(""));
            Capability existing = new Capability(models(media.path("image")), models(media.path("video")), models(media.path("voice")), models(media.path("vision")),
                    safeEndpoint(media.path("voiceEndpoint").asText("")), safeProtocol(media.path("imageProtocol").asText(""), ""),
                    safeProtocol(media.path("videoProtocol").asText(""), ""), safeProtocol(media.path("voiceProtocol").asText(""), ""),
                    setup.isBlank() ? officialSetup(provider) : setup,
                    billing.isBlank() ? officialBilling(provider) : billing);
            return officialOpenAi(provider) ? mergeOfficialDefaults(provider, existing) : existing;
        } catch (Exception ignored) {
            return officialOpenAi(provider) ? mergeOfficialDefaults(provider, Capability.empty())
                    : new Capability(List.of(), List.of(), List.of(), List.of(), "", "", "", "openai_audio_speech", officialSetup(provider), officialBilling(provider));
        }
    }

    public static boolean officialOpenAi(AiProvider provider) {
        if (provider == null) return false;
        if (provider.getKind() != null && provider.getKind() != com.douyin.mixcut.domain.ProviderKind.openai) return false;
        String base = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().trim().replaceAll("/+$", "");
        return "https://api.openai.com".equalsIgnoreCase(base);
    }

    public static Capability openAiOfficialDefaults() {
        return OPENAI_OFFICIAL_DEFAULTS;
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
            media.set("image", om.valueToTree(models(input.path("image"))));
            media.set("video", om.valueToTree(models(input.path("video"))));
            media.set("voice", om.valueToTree(models(input.path("voice"))));
            media.set("vision", om.valueToTree(models(input.path("vision"))));
            String imageProtocol = safeProtocol(input.path("imageProtocol").asText(""), "");
            String videoProtocol = safeProtocol(input.path("videoProtocol").asText(""), "");
            String voiceEndpoint = safeEndpoint(input.path("voiceEndpoint").asText(""));
            String voiceProtocol = safeProtocol(input.path("voiceProtocol").asText(""), "");
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

    private Capability mergeOfficialDefaults(AiProvider provider, Capability existing) {
        List<String> image = merge(existing.imageModels(), OPENAI_OFFICIAL_DEFAULTS.imageModels());
        List<String> video = merge(existing.videoModels(), OPENAI_OFFICIAL_DEFAULTS.videoModels());
        List<String> voice = merge(existing.voiceModels(), OPENAI_OFFICIAL_DEFAULTS.voiceModels());
        String setup = existing.setupUrl() == null || existing.setupUrl().isBlank() ? officialSetup(provider) : existing.setupUrl();
        String billing = existing.billingUrl() == null || existing.billingUrl().isBlank() ? officialBilling(provider) : existing.billingUrl();
        return new Capability(image, video, voice, existing.visionModels(), existing.voiceEndpoint(),
                existing.imageProtocol().isBlank() ? OPENAI_OFFICIAL_DEFAULTS.imageProtocol() : existing.imageProtocol(),
                existing.videoProtocol().isBlank() ? OPENAI_OFFICIAL_DEFAULTS.videoProtocol() : existing.videoProtocol(),
                existing.voiceProtocol(), setup, billing);
    }

    private List<String> merge(List<String> primary, List<String> fallback) {
        if (primary != null && !primary.isEmpty()) return primary;
        return fallback;
    }

    private String safeEndpoint(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String url = UrlGuard.validate(raw);
        if (!url.startsWith("https://")) throw new IllegalArgumentException("媒体 endpoint 必须使用 HTTPS");
        return url;
    }

    private String safeProtocol(String raw, String defaultValue) {
        String value = raw == null || raw.isBlank() ? defaultValue : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank()) return "";
        if (!List.of("openai_image_generation", "openai_video_generation", "openai_audio_speech", "dashscope_tts_http", "dashscope_tts_websocket").contains(value)) throw new IllegalArgumentException("不支持的媒体协议");
        return value;
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
