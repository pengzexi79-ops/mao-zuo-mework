package com.douyin.mixcut.web;

import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.domain.ProviderKind;
import com.douyin.mixcut.external.AiClient;
import com.douyin.mixcut.repository.Repositories.*;
import com.douyin.mixcut.security.CredentialCipher;
import com.douyin.mixcut.service.AiService;
import com.douyin.mixcut.service.CopyService;
import com.douyin.mixcut.service.MediaProviderCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiControllerTest {
    @Mock private AiProviderRepo providerRepo;
    @Mock private AiRouteRepo routeRepo;
    @Mock private AiLogRepo logRepo;
    @Mock private ProjectRepo projectRepo;
    @Mock private AiService aiService;
    @Mock private CopyService copyService;
    @Mock private CredentialCipher credentialCipher;
    @Mock private AiClient aiClient;

    private AiController controller;
    private MediaProviderCatalog mediaCatalog;

    @BeforeEach
    void setUp() {
        mediaCatalog = new MediaProviderCatalog(new ObjectMapper());
        controller = new AiController(providerRepo, routeRepo, logRepo, projectRepo, aiService, copyService,
                credentialCipher, mediaCatalog, aiClient);
        lenient().when(credentialCipher.encrypted(any())).thenReturn(false);
        lenient().when(credentialCipher.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void discoveryPreservesConfiguredModelsAndDefault() throws Exception {
        AiProvider provider = new AiProvider();
        provider.setId(7L);
        provider.setName("测试 Provider");
        provider.setKind(ProviderKind.openai);
        provider.setBaseUrl("https://api.example.com");
        provider.setApiKey("configured-secret");
        provider.setDefaultModel("manual-model");
        provider.setModels("{\"text\":[\"manual-model\"],\"media\":{\"image\":[\"adopted-image\"]}}");
        when(providerRepo.findById(7L)).thenReturn(Optional.of(provider));
        when(providerRepo.save(any(AiProvider.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiClient.discoverModels(provider)).thenReturn(new AiClient.ModelDiscovery(true,
                List.of("new-model"), List.of(), List.of(), List.of(), List.of(), null, 12, 1234));
        when(credentialCipher.encrypted("configured-secret")).thenReturn(false);

        R<Map<String, Object>> result = controller.discoverModels(7L);

        assertTrue(result.isOk());
        var root = new ObjectMapper().readTree(provider.getModels());
        assertEquals("new-model", provider.getDefaultModel());
        assertTrue(root.path("text").toString().contains("manual-model"));
        assertTrue(root.path("text").toString().contains("new-model"));
        assertEquals("adopted-image", root.path("media").path("image").path(0).asText());
        assertEquals("new-model", root.path("observed").path("text").path(0).asText());
    }

    @Test
    void draftDiscoveryUsesCurrentUnsavedAddressAndKeyWithoutPersistingThem() {
        AiProvider stored = new AiProvider();
        stored.setId(9L);
        stored.setName("旧配置");
        stored.setKind(ProviderKind.openai);
        stored.setBaseUrl("https://api.deepseek.com/v1");
        stored.setApiKey("old-secret");
        when(providerRepo.findById(9L)).thenReturn(Optional.of(stored));
        when(aiClient.discoverModels(any(AiProvider.class))).thenAnswer(invocation -> {
            AiProvider draft = invocation.getArgument(0);
            assertEquals("https://api.openai.com/v1", draft.getBaseUrl());
            assertEquals("new-secret", draft.getApiKey());
            return new AiClient.ModelDiscovery(true, List.of("new-model"), List.of(), List.of(), List.of(),
                    List.of(), null, 8, 1234);
        });

        AiProvider draft = new AiProvider();
        draft.setId(9L);
        draft.setName("当前草稿");
        draft.setKind(ProviderKind.openai);
        draft.setBaseUrl("https://api.openai.com/v1");
        draft.setApiKey("new-secret");

        R<Map<String, Object>> result = controller.discoverDraftModels(draft);

        assertTrue(result.isOk());
        assertEquals(List.of("new-model"), result.getData().get("textModels"));
        verify(providerRepo, never()).save(any(AiProvider.class));
    }

    @Test
    void officialWorkspaceDiscoveryPromotesMediaAndPrefersNewFlagshipModel() throws Exception {
        AiProvider provider = new AiProvider();
        provider.setId(6L);
        provider.setName("阿里云百炼工作空间");
        provider.setKind(ProviderKind.openai);
        provider.setBaseUrl("https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        provider.setApiKey("workspace-secret");
        provider.setModels("{}");
        when(providerRepo.findById(6L)).thenReturn(Optional.of(provider));
        when(providerRepo.save(any(AiProvider.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiClient.discoverModels(provider)).thenReturn(new AiClient.ModelDiscovery(true,
                List.of("qwen3.5-397b-a17b", "qwen3.8-flash", "qwen3.8-max"),
                List.of("qwen-image-max"), List.of("wan2.6-t2v"), List.of("MiniMax/speech-2.8-hd", "qwen3-tts-vc-2026-01-22"),
                List.of("qwen3-vl-plus"), null, 10, 1234));

        R<Map<String, Object>> result = controller.discoverModels(6L);

        assertTrue(result.isOk());
        assertEquals("qwen3.8-max", provider.getDefaultModel());
        var root = new ObjectMapper().readTree(provider.getModels());
        assertEquals("qwen-image-max", root.path("media").path("image").path(0).asText());
        assertEquals("wan2.6-t2v", root.path("media").path("video").path(0).asText());
        assertEquals(List.of("MiniMax/speech-2.8-hd"),
                new ObjectMapper().convertValue(root.path("media").path("voice"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        assertEquals(List.of("MiniMax/speech-2.8-hd"), result.getData().get("voiceModels"));
        assertEquals(List.of("MiniMax/speech-2.8-hd", "qwen3-tts-vc-2026-01-22"),
                ((Map<?, ?>) result.getData().get("observedMediaModels")).get("voice"));
        var minimaxVoiceRoute = mediaCatalog.read(provider).route("voice", "MiniMax/speech-2.8-hd");
        assertEquals("dashscope_minimax_tts_http", minimaxVoiceRoute.protocol());
        assertEquals("https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", minimaxVoiceRoute.endpoint());
    }

    @Test
    void officialDashScopeDiscoveryPromotesMediaAndKeepsMiniMaxBrandOutOfMiniPenalty() throws Exception {
        AiProvider provider = new AiProvider();
        provider.setId(7L);
        provider.setName("阿里云百炼标准 API");
        provider.setKind(ProviderKind.openai);
        provider.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setApiKey("standard-secret");
        provider.setModels("{}");
        when(providerRepo.findById(7L)).thenReturn(Optional.of(provider));
        when(providerRepo.save(any(AiProvider.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiClient.discoverModels(provider)).thenReturn(new AiClient.ModelDiscovery(true,
                List.of("qwen-turbo", "qwen-max"), List.of(), List.of(),
                List.of("qwen3-tts-flash", "MiniMax/speech-2.8-hd"), List.of(), null, 10, 1234));

        assertTrue(controller.discoverModels(7L).isOk());

        var root = new ObjectMapper().readTree(provider.getModels());
        assertEquals("qwen-max", provider.getDefaultModel());
        assertEquals("MiniMax/speech-2.8-hd", root.path("media").path("voice").path(0).asText());
        assertEquals("dashscope_minimax_tts_http",
                mediaCatalog.read(provider).route("voice", "MiniMax/speech-2.8-hd").protocol());
    }

    @Test
    void genericGatewayDiscoveryKeepsMediaAdvisory() throws Exception {
        AiProvider provider = new AiProvider();
        provider.setId(8L);
        provider.setName("未知中转");
        provider.setKind(ProviderKind.openai);
        provider.setBaseUrl("https://relay.example.com/v1");
        provider.setApiKey("relay-secret");
        provider.setModels("{}");
        when(providerRepo.findById(8L)).thenReturn(Optional.of(provider));
        when(providerRepo.save(any(AiProvider.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiClient.discoverModels(provider)).thenReturn(new AiClient.ModelDiscovery(true,
                List.of("chat-model"), List.of("image-model"), List.of(), List.of(), List.of(), null, 10, 1234));

        assertTrue(controller.discoverModels(8L).isOk());

        var root = new ObjectMapper().readTree(provider.getModels());
        assertTrue(root.path("media").path("image").isMissingNode());
        assertEquals("image-model", root.path("observed").path("image").path(0).asText());
    }

    @Test
    void createRejectsCodingPlanKeyBeforeSaving() {
        AiProvider provider = new AiProvider();
        provider.setName("错误订阅配置");
        provider.setKind(ProviderKind.openai);
        provider.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setApiKey("sk-sp-test");

        R<Map<String, Object>> result = controller.create(provider);

        assertTrue(!result.isOk());
        assertTrue(result.getMessage().contains("标准 API Key"));
        verify(providerRepo, never()).save(any(AiProvider.class));
    }

    @Test
    void unrelatedProviderUpdateDoesNotRevalidateOrRediscoverStoredCredential() {
        AiProvider provider = providerWithStoredSubscriptionKey();
        when(providerRepo.findById(5L)).thenReturn(Optional.of(provider));
        when(providerRepo.save(any(AiProvider.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiProvider update = new AiProvider();
        update.setName("仅修改名称");
        update.setKind(ProviderKind.openai);
        update.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/");
        update.setEnabled(false);

        R<Map<String, Object>> result = controller.update(5L, update);

        assertTrue(result.isOk());
        assertEquals("仅修改名称", provider.getName());
        assertEquals(false, provider.getEnabled());
        verify(aiClient, never()).discoverModels(any(AiProvider.class));
    }

    @Test
    void changingCredentialBindingStillRejectsKnownMismatch() {
        AiProvider provider = providerWithStoredSubscriptionKey();
        provider.setBaseUrl("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        when(providerRepo.findById(5L)).thenReturn(Optional.of(provider));

        AiProvider update = new AiProvider();
        update.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");

        R<Map<String, Object>> result = controller.update(5L, update);

        assertTrue(!result.isOk());
        assertTrue(result.getMessage().contains("标准 API Key"));
        verify(providerRepo, never()).save(any(AiProvider.class));
    }

    private AiProvider providerWithStoredSubscriptionKey() {
        AiProvider provider = new AiProvider();
        provider.setId(5L);
        provider.setName("阿里云百炼");
        provider.setKind(ProviderKind.openai);
        provider.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setApiKey("sk-sp-stored");
        provider.setPriority(10);
        provider.setEnabled(true);
        return provider;
    }
}
