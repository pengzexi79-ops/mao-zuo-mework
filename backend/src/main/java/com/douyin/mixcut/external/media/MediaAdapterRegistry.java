package com.douyin.mixcut.external.media;

import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.service.MediaProviderCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** Resolves only persisted, named wire protocols to executable adapters. */
@Component
public class MediaAdapterRegistry {
    public static final String OPENAI_IMAGE_GENERATION = "openai_image_generation";
    public static final String OPENAI_VIDEO_GENERATION = "openai_video_generation";
    public static final String OPENAI_AUDIO_SPEECH = "openai_audio_speech";
    public static final String MEDIA_PROTOCOL_UNSUPPORTED = "MEDIA_PROTOCOL_UNSUPPORTED";

    private final List<MediaAdapter> adapters;

    @Autowired
    public MediaAdapterRegistry(OpenAiCompatibleMediaAdapter openAiAdapter) {
        this(List.of(openAiAdapter));
    }

    public MediaAdapterRegistry(List<? extends MediaAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public OpenAiCompatibleMediaAdapter adapterFor(AiProvider provider, MediaAdapter.Action action, MediaProviderCatalog.Capability capability) {
        if (action == null) throw unsupported("");
        String operation = switch (action) {
            case IMAGE -> "image";
            case VIDEO_SUBMIT, VIDEO_POLL, VIDEO_DOWNLOAD -> "video";
            case VOICE_SUBMIT -> "voice";
        };
        return adapterFor(provider, operation, capability);
    }

    public OpenAiCompatibleMediaAdapter adapterFor(AiProvider provider, String operation, MediaProviderCatalog.Capability capability) {
        // A null capability can only occur in legacy unit-test doubles; persisted provider
        // configurations are always read through MediaProviderCatalog before execution.
        if (capability == null) return adapters.stream()
                .filter(OpenAiCompatibleMediaAdapter.class::isInstance)
                .map(OpenAiCompatibleMediaAdapter.class::cast)
                .findFirst()
                .orElseThrow(() -> unsupported(""));
        String protocol = capability.protocol(operation);
        if (!List.of(OPENAI_IMAGE_GENERATION, OPENAI_VIDEO_GENERATION, OPENAI_AUDIO_SPEECH).contains(protocol)) {
            throw unsupported(protocol);
        }
        return adapters.stream()
                .filter(adapter -> adapter.supportsProtocol(protocol))
                .filter(OpenAiCompatibleMediaAdapter.class::isInstance)
                .map(OpenAiCompatibleMediaAdapter.class::cast)
                .findFirst()
                .orElseThrow(() -> unsupported(protocol));
    }

    public OpenAiCompatibleMediaAdapter adapterFor(AiProvider provider, String operation, MediaProviderCatalog catalog) {
        return adapterFor(provider, operation, catalog.read(provider));
    }

    public static OpenAiCompatibleMediaAdapter.MediaAdapterException unsupported(String protocol) {
        return new OpenAiCompatibleMediaAdapter.MediaAdapterException(MEDIA_PROTOCOL_UNSUPPORTED,
                "媒体协议未注册可执行 adapter：" + (protocol == null || protocol.isBlank() ? "未定义" : protocol));
    }
}
