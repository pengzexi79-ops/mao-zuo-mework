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
    void suppliesOfficialOpenAiMediaDefaultsWhenProviderHasNoMediaJson() {
        AiProvider openai = provider("https://api.openai.com");

        var capability = catalog.read(openai);

        assertTrue(capability.imageModels().contains("gpt-image-1"));
        assertTrue(capability.videoModels().contains("sora-2"));
        assertTrue(capability.voiceModels().contains("gpt-4o-mini-tts"));
        assertEquals("https://platform.openai.com/api-keys", capability.setupUrl());
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
