package com.douyin.mixcut.external.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleMediaAdapterTest {
    @Test
    void imageUsesFixedEndpointBearerHeaderAndOpenAiBody() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of(), "{\"data\":[{\"b64_json\":\"abc\"}]}".getBytes(StandardCharsets.UTF_8));
        });

        var result = adapter.submitImage(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", ""),
                "a product", "image-model", "1024x1024", "medium");

        assertEquals("abc", result.base64());
        assertEquals("https://api.openai.com/v1/images/generations", captured.get().url());
        assertEquals("Bearer secret", captured.get().headers().get("Authorization"));
        String body = new String(captured.get().body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"model\":\"image-model\""));
        assertTrue(body.contains("\"prompt\":\"a product\""));
    }

    @Test
    void preservesArkApiV3MediaRouteCompatibility() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of(), "{\"id\":\"remote-video-ark\"}".getBytes(StandardCharsets.UTF_8));
        });

        adapter.submitVideo(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com/api/v3", "secret", ""),
                "a product", "video-model", "1280x720", 4);

        assertEquals("https://api.openai.com/api/v3/videos", captured.get().url());
    }

    @Test
    void videoUsesFixedMultipartSubmissionPath() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of(), "{\"id\":\"remote-video-1\"}".getBytes(StandardCharsets.UTF_8));
        });

        var result = adapter.submitVideo(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", ""),
                "a product", "video-model", "1280x720", 4);

        assertEquals("remote-video-1", result.remoteTaskId());
        assertEquals("https://api.openai.com/v1/videos", captured.get().url());
        assertEquals("Bearer secret", captured.get().headers().get("Authorization"));
        assertTrue(captured.get().contentType().startsWith("multipart/form-data"));
        assertTrue(new String(captured.get().body(), StandardCharsets.UTF_8).contains("name=\"seconds\""));
    }

    @Test
    void pollsAndDownloadsVideoUsingFixedRemotePaths() throws Exception {
        java.util.List<MediaHttpTransport.Request> requests = new java.util.ArrayList<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            requests.add(request);
            if (request.url().endsWith("/content")) return new MediaHttpTransport.Response(200, Map.of("Content-Type", "video/mp4"), new byte[4096]);
            return new MediaHttpTransport.Response(200, Map.of(), "{\"status\":\"completed\",\"progress\":100}".getBytes(StandardCharsets.UTF_8));
        });
        var provider = new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", "");

        var poll = adapter.pollVideo(provider, "remote-1");
        var download = adapter.downloadVideo(provider, "remote-1");

        assertEquals(OpenAiCompatibleMediaAdapter.VideoState.SUCCEEDED, poll.state());
        assertEquals(100, poll.progress());
        assertEquals(4096, download.bytes().length);
        assertEquals("https://api.openai.com/v1/videos/remote-1", requests.get(0).url());
        assertEquals("https://api.openai.com/v1/videos/remote-1/content", requests.get(1).url());
        assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () -> adapter.pollVideo(provider, "../escape"));
    }

    @Test
    void voiceMapsRemoteFailuresToStableCode() {
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request ->
                new MediaHttpTransport.Response(429, Map.of(), new byte[0]));

        var error = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", "/v1/audio/speech"),
                        "hello", "voice-model", "coral", ""));

        assertEquals("RATE_LIMITED", error.code());
    }

    @Test
    void rejectsNonOpenAiCompatibleVoiceEndpointBeforeTransport() {
        assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", "/api/tts"));
    }
}
