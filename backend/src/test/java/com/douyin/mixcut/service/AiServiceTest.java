package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.external.AiClient;
import com.douyin.mixcut.repository.Repositories.AiLogRepo;
import com.douyin.mixcut.repository.Repositories.AiProviderRepo;
import com.douyin.mixcut.repository.Repositories.AiRouteRepo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiServiceTest {
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
