package com.douyin.mixcut.external.media;

import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.service.MediaProviderCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class MediaAdapterRegistryTest {
    private final OpenAiCompatibleMediaAdapter openAi = mock(OpenAiCompatibleMediaAdapter.class);
    private final MediaAdapterRegistry registry = new MediaAdapterRegistry(List.of(openAi));
    private final AiProvider provider = new AiProvider();

    @Test
    void selectsRegisteredOpenAiAdapterForExplicitProtocols() {
        var capability = capability("openai_image_generation", "openai_video_generation", "openai_audio_speech");
        org.mockito.Mockito.when(openAi.supportsProtocol(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        assertSame(openAi, registry.adapterFor(provider, "image", capability));
        assertSame(openAi, registry.adapterFor(provider, "video", capability));
        assertSame(openAi, registry.adapterFor(provider, "voice", capability));
    }

    @Test
    void rejectsDashScopeHttpAndQwenWebsocketProtocols() {
        org.mockito.Mockito.when(openAi.supportsProtocol(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        assertSame(openAi, registry.adapterFor(provider, "voice", capability("", "", "dashscope_tts_http")));
        assertUnsupported(capability("", "", "dashscope_tts_websocket"), "voice");
    }

    @Test
    void rejectsRelayAndUndefinedProtocols() {
        assertUnsupported(capability("relay_custom", "", ""), "image");
        assertUnsupported(capability("", "", ""), "voice");
    }

    private void assertUnsupported(MediaProviderCatalog.Capability capability, String operation) {
        var error = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class,
                () -> registry.adapterFor(provider, operation, capability));
        assertEquals(MediaAdapterRegistry.MEDIA_PROTOCOL_UNSUPPORTED, error.code());
    }

    private MediaProviderCatalog.Capability capability(String image, String video, String voice) {
        return new MediaProviderCatalog.Capability(List.of("model"), List.of("model"), List.of("model"), List.of(), "",
                image, video, voice, "", "");
    }
}
