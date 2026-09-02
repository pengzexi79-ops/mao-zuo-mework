package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaProviderCatalogTest {
    private final MediaProviderCatalog catalog = new MediaProviderCatalog(new ObjectMapper());

    @Test
    void keepsVisionSeparateAndPersistsVoiceProtocol() throws Exception {
        String merged = catalog.mergeMediaConfig("{\"text\":[\"qwen-plus\"]}", "{\"image\":[\"qwen-image-2.0-pro\"],\"video\":[],\"voice\":[\"qwen3-tts-flash\"],\"vision\":[\"qwen3-vl-plus\"],\"voiceProtocol\":\"dashscope_tts_http\",\"voiceEndpoint\":\"https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation\"}");
        AiProvider qwen = provider("https://dashscope.aliyuncs.com/compatible-mode");
        qwen.setModels(merged);
        var capability = catalog.read(qwen);
        assertTrue(capability.imageModels().contains("qwen-image-2.0-pro"));
        assertTrue(capability.visionModels().contains("qwen3-vl-plus"));
        assertEquals("dashscope_tts_http", capability.voiceProtocol());
    }

    @Test
    void routesEachDashScopeImageModelWithoutDroppingOtherFamilies() {
        AiProvider provider = provider("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setModels("{\"media\":{\"image\":[\"qwen-image-3.0-pro\",\"qwen-image-2.0-pro\",\"qwen-image-max\",\"wan2.7-image\",\"qwen-image-edit-max\"],\"imageProtocol\":\"openai_image_generation\"}}");

        var capability = catalog.read(provider);

        assertEquals(java.util.List.of("qwen-image-3.0-pro", "qwen-image-2.0-pro", "qwen-image-max", "wan2.7-image", "qwen-image-edit-max"), capability.imageModels());
        assertEquals("dashscope_image_http", capability.protocol("image", "qwen-image-3.0-pro"));
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                capability.endpoint("image", "qwen-image-2.0-pro"));
        assertEquals("dashscope_image_task_http", capability.protocol("image", "qwen-image-max"));
        assertEquals("dashscope_image_http", capability.protocol("image", "wan2.7-image"));
        assertEquals("dashscope_image_edit_http", capability.protocol("image", "qwen-image-edit-max"));
    }

    @Test
    void preservesDashScopeWorkspaceHostAndRoutesQwenAndMiniMaxVoiceModels() {
        AiProvider provider = provider("https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        provider.setModels("{\"media\":{\"image\":[\"qwen-image-max\"],\"video\":[\"wan2.6-t2v\"],\"voice\":[\"qwen3-tts-flash\",\"MiniMax/speech-2.8-hd\",\"speech-2.8-hd\"]}}");

        var capability = catalog.read(provider);

        assertTrue(capability.endpoint("image", "qwen-image-max").startsWith("https://ws-example.cn-beijing.maas.aliyuncs.com/"));
        assertTrue(capability.endpoint("video", "wan2.6-t2v").startsWith("https://ws-example.cn-beijing.maas.aliyuncs.com/"));
        assertTrue(capability.endpoint("voice", "qwen3-tts-flash").startsWith("https://ws-example.cn-beijing.maas.aliyuncs.com/"));
        assertEquals("dashscope_tts_http", capability.protocol("voice", "qwen3-tts-flash"));
        assertEquals("dashscope_minimax_tts_http", capability.protocol("voice", "MiniMax/speech-2.8-hd"));
        assertEquals("dashscope_minimax_tts_http", capability.protocol("voice", "speech-2.8-hd"));
        assertTrue(capability.endpoint("voice", "MiniMax/speech-2.8-hd")
                .startsWith("https://ws-example.cn-beijing.maas.aliyuncs.com/"));
    }

    @Test
    void refreshesAutomaticRoutesButKeepsManualOverrides() {
        AiProvider provider = provider("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setModels("{\"media\":{\"image\":[\"qwen-image-max\",\"custom-image\"],\"routes\":{\"image\":{\"qwen-image-max\":{\"protocol\":\"openai_image_generation\",\"source\":\"provider-default\"},\"custom-image\":{\"protocol\":\"openai_image_generation\",\"endpoint\":\"/custom/images\",\"source\":\"saved\"}}}}}");

        var capability = catalog.read(provider);

        assertEquals("dashscope_image_task_http", capability.protocol("image", "qwen-image-max"));
        assertEquals("/custom/images", capability.endpoint("image", "custom-image"));
    }

    @Test
    void synchronousVoiceExecutionRejectsRealtimeCloneAndDesignModels() {
        AiProvider provider = provider("https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");

        assertTrue(catalog.supportsInferredRoute(provider, "voice", "qwen3-tts-flash"));
        assertTrue(catalog.supportsInferredRoute(provider, "voice", "MiniMax/speech-2.8-hd"));
        for (String model : java.util.List.of(
                "qwen3-tts-instruct-flash-realtime-2026-01-22",
                "qwen3-tts-vc-2026-01-22",
                "qwen3-tts-vd-2026-01-22",
                "MiniMax/voice-clone",
                "MiniMax/voice-design")) {
            assertTrue(!catalog.supportsInferredRoute(provider, "voice", model));
        }
    }

    @Test
    void separatesQwenAndMiniMaxVoiceRoutesInsideOneDashScopeProvider() {
        AiProvider provider = provider("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setModels("{\"media\":{\"voice\":[\"qwen3-tts-flash\",\"MiniMax/speech-2.8-hd\"]}}");

        var capability = catalog.read(provider);

        assertEquals("dashscope_tts_http", capability.protocol("voice", "qwen3-tts-flash"));
        assertEquals("dashscope_minimax_tts_http", capability.protocol("voice", "MiniMax/speech-2.8-hd"));
    }

    @Test
    void keepsMiniMaxModelOpenAiCompatibleOnUnknownGateway() {
        AiProvider provider = provider("https://relay.example.com/v1");
        provider.setModels("{\"media\":{\"voice\":[\"MiniMax/speech-2.8-hd\"]}}");

        assertEquals("openai_audio_speech", catalog.read(provider).protocol("voice", "MiniMax/speech-2.8-hd"));
    }

    @Test
    void routesWanVideoToDashScopeAsyncTaskContract() {
        AiProvider provider = provider("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setModels("{\"media\":{\"video\":[\"wan2.6-t2v\"]}}");

        var capability = catalog.read(provider);

        assertEquals("dashscope_video_task_http", capability.protocol("video", "wan2.6-t2v"));
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis",
                capability.endpoint("video", "wan2.6-t2v"));
    }

    @Test
    void mergesMediaConfigWithoutDroppingLegacyTextModels() throws Exception {
        String merged = catalog.mergeMediaConfig("[\"gpt-4o-mini\"]", "{\"image\":[\"gpt-image-1\"],\"video\":[\"sora-2\"],\"voice\":[\"tts-1\"],\"setupUrl\":\"https://platform.openai.com/api-keys\"}");

        var root = new ObjectMapper().readTree(merged);
        assertEquals("gpt-4o-mini", root.path("text").path(0).asText());
        assertEquals("gpt-image-1", root.path("media").path("image").path(0).asText());
        assertEquals("sora-2", root.path("media").path("video").path(0).asText());
        assertEquals("tts-1", root.path("media").path("voice").path(0).asText());
    }

    @Test
    void adoptsOnlyObservedMediaModelsIntoExecutionAllowlist() throws Exception {
        String existing = "{\"text\":[\"gpt-5.6-terra\"],\"observed\":{\"image\":[\"seedream-4\",\"not-selected\"]},\"media\":{\"image\":[\"old-image\"]}}";
        String merged = catalog.adoptObservedMedia(existing, "image", java.util.List.of("seedream-4"));
        var root = new ObjectMapper().readTree(merged);
        assertEquals("seedream-4", root.path("media").path("image").path(0).asText());
        assertEquals("gpt-5.6-terra", root.path("text").path(0).asText());
        assertThrows(IllegalArgumentException.class, () -> catalog.adoptObservedMedia(existing, "video", java.util.List.of("not-observed")));
    }

    @Test
    void suppliesOfficialLinksForVolcanoAndQwenTemplates() {
        AiProvider volcano = provider("https://ark.cn-beijing.volces.com/api/v3");
        AiProvider qwen = provider("https://dashscope.aliyuncs.com/compatible-mode");

        assertEquals("https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey", catalog.read(volcano).setupUrl());
        assertEquals("https://console.volcengine.com/finance/expense", catalog.read(volcano).billingUrl());
        assertEquals("https://dashscope.console.aliyun.com/apiKey", catalog.read(qwen).setupUrl());
        assertEquals("https://usercenter.console.aliyun.com/#/manage-account/payment", catalog.read(qwen).billingUrl());
    }

    @Test
    void preservesUnregisteredVoiceProtocolForServiceLevelRejection() {
        AiProvider provider = provider("https://api.openai.com");
        provider.setModels("{\"media\":{\"voice\":[\"voice-model\"],\"voiceProtocol\":\"dashscope_tts_http\"}}");

        assertEquals("dashscope_tts_http", catalog.read(provider).voiceProtocol());
    }

    @Test
    void doesNotEnablePaidMediaUntilOfficialModelsAreExplicitlyConfigured() {
        AiProvider openai = provider("https://api.openai.com");

        var capability = catalog.read(openai);

        assertTrue(capability.imageModels().isEmpty());
        assertTrue(capability.videoModels().isEmpty());
        assertTrue(capability.voiceModels().isEmpty());
        assertTrue(capability.imageProtocol().isBlank());
        assertTrue(capability.videoProtocol().isBlank());
        assertTrue(capability.voiceProtocol().isBlank());
        assertEquals("https://platform.openai.com/api-keys", capability.setupUrl());
    }

    @Test
    void assignsCompatibleDefaultsToLegacyManualMediaModels() {
        AiProvider provider = provider("https://relay.example.com/v1");
        provider.setModels("{\"text\":[\"gpt-5.6-terra\"],\"media\":{\"image\":[\"gpt-5.6-terra\"],\"video\":[\"gpt-5.6-terra\"],\"voice\":[\"gpt-5.6-terra\"]}}");

        var capability = catalog.read(provider);

        assertEquals("openai_image_generation", capability.imageProtocol());
        assertEquals("openai_video_generation", capability.videoProtocol());
        assertEquals("openai_audio_speech", capability.voiceProtocol());
    }

    @Test
    void keepsManuallyDeclaredModelEvenWhenDiscoveryDidNotReturnIt() throws Exception {
        String merged = catalog.mergeMediaConfig("{\"text\":[\"gpt-5.6-terra\"],\"observed\":{\"image\":[]}}",
                "{\"image\":[\"gpt-5.6-terra\"]}");
        var root = new ObjectMapper().readTree(merged);

        assertEquals("gpt-5.6-terra", root.path("media").path("image").path(0).asText());
        assertEquals("openai_image_generation", root.path("media").path("imageProtocol").asText());
    }

    @Test
    void persistsModelRouteAndLetsItOverrideProviderDefault() {
        String merged = catalog.mergeMediaConfig("{}", "{\"voice\":[\"custom-voice\"],\"voiceProtocol\":\"openai_audio_speech\",\"routes\":{\"voice\":{\"custom-voice\":{\"protocol\":\"dashscope_minimax_tts_http\",\"endpoint\":\"https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation\",\"confidence\":\"high\",\"source\":\"saved\"}}}}");
        AiProvider provider = provider("https://relay.example.com/v1");
        provider.setModels(merged);

        var route = catalog.read(provider).route("voice", "custom-voice");

        assertEquals("dashscope_minimax_tts_http", route.protocol());
        assertEquals("high", route.confidence());
        assertEquals("saved", route.source());
    }

    @Test
    void acceptsRelativeAndHttpsMediaEndpointsButRejectsUnsafePaths() {
        String merged = catalog.mergeMediaConfig("{}", "{\"image\":[\"image-model\"],\"imageEndpoint\":\"/custom/images\",\"videoEndpoint\":\"https://api.openai.com/video\"}");
        var provider = provider("https://relay.example.com/v1");
        provider.setModels(merged);

        assertEquals("/custom/images", catalog.read(provider).imageEndpoint());
        assertEquals("https://api.openai.com/video", catalog.read(provider).videoEndpoint());
        assertThrows(IllegalArgumentException.class, () -> catalog.mergeMediaConfig("{}", "{\"image\":[\"m\"],\"imageEndpoint\":\"/../private\"}"));
    }

    @Test
    void rejectsUnsafeOfficialLinks() {
        assertThrows(IllegalArgumentException.class, () -> catalog.mergeMediaConfig("{}", "{\"setupUrl\":\"http://127.0.0.1:8080/key\"}"));
    }

    private AiProvider provider(String baseUrl) {
        AiProvider provider = new AiProvider();
        provider.setBaseUrl(baseUrl);
        provider.setModels("{}");
        return provider;
    }
}
