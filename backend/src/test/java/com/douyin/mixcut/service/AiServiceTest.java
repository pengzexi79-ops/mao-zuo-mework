package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.external.AiClient;
import com.douyin.mixcut.repository.Repositories.AiLogRepo;
import com.douyin.mixcut.repository.Repositories.AiProviderRepo;
import com.douyin.mixcut.repository.Repositories.AiRouteRepo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiServiceTest {
    @Test
    void mediaOnlyProviderDoesNotFailTextTestForMissingModel() {
        AiProviderRepo providers = mock(AiProviderRepo.class);
        AiService service = new AiService(providers, mock(AiRouteRepo.class), mock(AiLogRepo.class), mock(AiClient.class));
        AiProvider provider = new AiProvider();
        provider.setId(9L);
        provider.setName("图片供应商");
        provider.setModels("{\"text\":[],\"media\":{\"image\":[\"gpt-image-2\"]}}");
        when(providers.findById(9L)).thenReturn(java.util.Optional.of(provider));

        AiService.Answer result = service.test(9L, null);

        assertTrue(result.ok());
        assertEquals("gpt-image-2", result.model());
        assertTrue(result.text().contains("媒体专用"));
    }

    @Test
    void readinessRequiresCredentialAndDeclaredTextModel() {
        AiProviderRepo providers = mock(AiProviderRepo.class);
        AiService service = new AiService(providers, mock(AiRouteRepo.class), mock(AiLogRepo.class), mock(AiClient.class));
        AiProvider provider = new AiProvider();
        provider.setEnabled(true);
        provider.setDefaultModel("gpt-4o-mini");

        when(providers.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(provider));
        assertFalse(service.ready());

        provider.setApiKey("configured-secret");
        assertTrue(service.ready());

        provider.setDefaultModel("");
        provider.setModels("{}");
        assertFalse(service.ready());
    }
}
