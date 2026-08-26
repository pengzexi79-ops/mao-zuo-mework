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

    @BeforeEach
    void setUp() {
        controller = new AiController(providerRepo, routeRepo, logRepo, projectRepo, aiService, copyService,
                credentialCipher, new MediaProviderCatalog(new ObjectMapper()), aiClient);
        when(credentialCipher.encrypted(any())).thenReturn(false);
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
        assertEquals("manual-model", provider.getDefaultModel());
        assertTrue(root.path("text").toString().contains("manual-model"));
        assertTrue(root.path("text").toString().contains("new-model"));
        assertEquals("adopted-image", root.path("media").path("image").path(0).asText());
        assertEquals("new-model", root.path("observed").path("text").path(0).asText());
    }
}
