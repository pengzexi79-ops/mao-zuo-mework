package com.douyin.mixcut.external;

import com.douyin.mixcut.domain.ProviderKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientTest {

    @Test
    void volcanoArkCompatibleBaseDoesNotDuplicateVersionPath() {
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                AiClient.buildValidatedUrl("https://ark.cn-beijing.volces.com/api/v3", "/v1/chat/completions"));
    }

    @Test
    void openAiCompatibleDefaultsIncludeChinesePrimaryProviders() {
        var models = AiClient.defaultModels(ProviderKind.openai);
        assertTrue(models.contains("doubao-seed-1-6-250615"));
        assertTrue(models.contains("qwen-plus"));
        assertTrue(models.contains("gpt-4o-mini"));
    }
}
