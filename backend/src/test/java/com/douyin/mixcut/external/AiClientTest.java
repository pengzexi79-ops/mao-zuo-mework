package com.douyin.mixcut.external;

import com.douyin.mixcut.domain.ProviderKind;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientTest {

    @Test
    void volcanoArkCompatibleBaseDoesNotDuplicateVersionPath() {
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                AiClient.buildValidatedUrl("https://ark.cn-beijing.volces.com/api/v3", "/v1/chat/completions"));
    }

    @Test
    void qwenImageIsImageGenerationButQwenVlRemainsVisionOnly() {
        assertTrue(AiClient.isImageGenerationModel("qwen-image-2.0-pro", Set.of()));
        assertTrue(AiClient.isImageGenerationModel("wan2.7-image", Set.of()));
        assertTrue(!AiClient.isImageGenerationModel("qwen-image-edit-max", Set.of("image_generation")));
        assertTrue(AiClient.isImageEditingModel("qwen-image-edit-max"));
        assertTrue(!AiClient.isImageGenerationModel("qwen3-vl-plus", Set.of()));
        assertTrue(!AiClient.isImageGenerationModel("qwen-plus", Set.of()));
    }

    @Test
    void openAiCompatibleDefaultsIncludeChinesePrimaryProviders() {
        var models = AiClient.defaultModels(ProviderKind.openai);
        assertTrue(models.contains("doubao-seed-1-6-250615"));
        assertTrue(models.contains("qwen-plus"));
        assertTrue(models.contains("gpt-4o-mini"));
    }

    @Test
    void classifiesProviderHttpFailuresWithoutCollapsingTheirMeaning() {
        assertEquals("AI_AUTH_REQUIRED", AiClient.classifyHttpStatus(401));
        assertEquals("AI_ENDPOINT_UNSUPPORTED", AiClient.classifyHttpStatus(404));
        assertEquals("AI_RATE_LIMITED", AiClient.classifyHttpStatus(429));
        assertEquals("AI_REMOTE_SERVER_ERROR", AiClient.classifyHttpStatus(503));
        assertEquals("AI_REQUEST_REJECTED", AiClient.classifyHttpStatus(400));
    }

    @Test
    void rejectsOnlyKnownDashScopeCredentialMismatchAndKeepsOtherProvidersOpen() {
        var dashScope = assertThrows(IllegalStateException.class, () ->
                AiClient.validateProviderCredentialCompatibility(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderKind.openai, "sk-sp-test"));
        assertTrue(dashScope.getMessage().contains("标准 API Key"));

        var dashScopeO1 = assertThrows(IllegalStateException.class, () ->
                AiClient.validateProviderCredentialCompatibility(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderKind.openai, "o1_subscription"));
        assertTrue(dashScopeO1.getMessage().contains("标准 API Key"));

        var dashScopeWorkspace = assertThrows(IllegalStateException.class, () ->
                AiClient.validateProviderCredentialCompatibility(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderKind.openai, "sk-ws-test"));
        assertTrue(dashScopeWorkspace.getMessage().contains("工作空间"));

        assertDoesNotThrow(() ->
                AiClient.validateProviderCredentialCompatibility(
                        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", ProviderKind.openai, "o1_subscription"));

        assertDoesNotThrow(() ->
                AiClient.validateProviderCredentialCompatibility("https://example.com/v1", ProviderKind.openai, "o1_bootstrap"));
        assertDoesNotThrow(() -> AiClient.validateProviderCredentialCompatibility(
                "https://relay.example.com/v1", ProviderKind.openai, "sk-sp-relay-test"));
        assertDoesNotThrow(() -> AiClient.validateProviderCredentialCompatibility(
                "https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderKind.openai, "sk-standard-api-key"));
    }
}
