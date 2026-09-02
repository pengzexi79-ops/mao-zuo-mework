package com.douyin.mixcut.external.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(body.contains("\"response_format\":\"b64_json\""));
        assertTrue(body.contains("\"output_format\":\"png\""));
    }

    @Test
    void dashScopeImageUsesNativeMultimodalContractAndParsesImageUrl() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of(),
                    "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://cdn.example.com/result.png\"}]}}]}}".getBytes(StandardCharsets.UTF_8));
        });

        var result = adapter.submitImage(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://dashscope.aliyuncs.com", "secret",
                        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                        "", "", "openai_audio_speech", "dashscope_image_http"),
                "a product", "qwen-image-3.0-pro", "1024x1536", "high");

        assertEquals("https://cdn.example.com/result.png", result.url());
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", captured.get().url());
        var body = new ObjectMapper().readTree(captured.get().body());
        assertEquals("a product", body.path("input").path("messages").path(0).path("content").path(0).path("text").asText());
        assertEquals("1024*1536", body.path("parameters").path("size").asText());
        assertFalse(body.has("prompt"));
    }

    @Test
    void dashScopeLegacyImageUsesAsyncTaskContract() throws Exception {
        List<MediaHttpTransport.Request> captured = new ArrayList<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.add(request);
            if ("POST".equals(request.method())) {
                return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                        "{\"output\":{\"task_id\":\"image-task-1\"}}".getBytes(StandardCharsets.UTF_8));
            }
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"url\":\"https://cdn.example.com/async.png\"}]}}".getBytes(StandardCharsets.UTF_8));
        });
        var provider = new OpenAiCompatibleMediaAdapter.ProviderContext(
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "secret",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis",
                "", "", "openai_audio_speech", "dashscope_image_task_http", "openai_video_generation");

        var result = adapter.submitImage(provider, "a product", "qwen-image-max", "1024x1024", "medium");

        assertEquals("https://cdn.example.com/async.png", result.url());
        assertEquals("enable", captured.get(0).headers().get("X-DashScope-Async"));
        assertEquals("https://dashscope.aliyuncs.com/api/v1/tasks/image-task-1", captured.get(1).url());
    }

    @Test
    void missingMediaEndpointHasStableActionableErrorCode() {
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request ->
                new MediaHttpTransport.Response(404, Map.of(), new byte[0]));

        var error = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                adapter.submitImage(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", ""),
                        "a product", "image-model", "1024x1024", "medium"));

        assertEquals("MEDIA_ENDPOINT_NOT_FOUND", error.code());
        assertTrue(error.getMessage().contains("协议不匹配"));
    }

    @Test
    void imageUsesConfiguredRelativeEndpoint() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of(), "{\"data\":[{\"b64_json\":\"abc\"}]}".getBytes(StandardCharsets.UTF_8));
        });

        adapter.submitImage(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com/v1", "secret", "/custom/image", "/custom/video", "/custom/voice"),
                "a product", "image-model", "1024x1024", "medium");

        assertEquals("https://api.openai.com/v1/custom/image", captured.get().url());
    }

    @Test
    void imageAcceptsDataUrlAndNestedBase64Response() throws Exception {
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request ->
                new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                        "{\"result\":{\"image\":{\"base64\":\"data:image/png;base64,QUJD\"}}}".getBytes(StandardCharsets.UTF_8)));

        var result = adapter.submitImage(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", ""),
                "a product", "image-model", "1024x1024", "medium");

        assertEquals("QUJD", result.base64());
        assertEquals("", result.url());
    }

    @Test
    void imageAcceptsDirectBinaryResponse() throws Exception {
        byte[] png = new byte[] { (byte) 0x89, 'P', 'N', 'G' };
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request ->
                new MediaHttpTransport.Response(200, Map.of("Content-Type", "image/png"), png));

        var result = adapter.submitImage(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", ""),
                "a product", "image-model", "1024x1024", "medium");

        assertEquals(java.util.Base64.getEncoder().encodeToString(png), result.base64());
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
    void videoAcceptsSynchronousNestedBase64Response() throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 });
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request ->
                new MediaHttpTransport.Response(200, Map.of(),
                        ("{\"output\":{\"video\":\"data:video/mp4;base64," + encoded + "\"}}").getBytes(StandardCharsets.UTF_8)));

        var result = adapter.submitVideo(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", ""),
                "a product", "video-model", "1280x720", 4);

        assertEquals("", result.remoteTaskId());
        assertEquals(encoded, result.base64());
        assertEquals("", result.url());
    }

    @Test
    void videoUsesConfiguredEndpointForSubmitPollAndDownload(@TempDir Path tempDir) throws Exception {
        List<MediaHttpTransport.Request> requests = new ArrayList<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), new MediaHttpTransport() {
            @Override
            public Response execute(Request request) {
                requests.add(request);
                byte[] body = requests.size() == 1
                        ? "{\"id\":\"remote-custom\"}".getBytes(StandardCharsets.UTF_8)
                        : "{\"status\":\"completed\",\"progress\":100}".getBytes(StandardCharsets.UTF_8);
                return new Response(200, Map.of(), body);
            }

            @Override
            public DownloadResponse download(Request request, Path staging, long maxBytes) throws Exception {
                requests.add(request);
                Files.write(staging, new byte[4096]);
                return new DownloadResponse(200, Map.of("Content-Type", "video/mp4"), 4096);
            }
        });
        var provider = new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", "/image", "/custom/videos", "/voice");

        adapter.submitVideo(provider, "a product", "video-model", "1280x720", 4);
        adapter.pollVideo(provider, "remote-custom");
        adapter.downloadVideo(provider, "remote-custom", tempDir.resolve("video.part"), 10_000);

        assertEquals("https://api.openai.com/custom/videos", requests.get(0).url());
        assertEquals("https://api.openai.com/custom/videos/remote-custom", requests.get(1).url());
        assertEquals("https://api.openai.com/custom/videos/remote-custom/content", requests.get(2).url());
    }

    @Test
    void pollsAndStreamsVideoUsingFixedRemotePaths(@TempDir Path tempDir) throws Exception {
        List<MediaHttpTransport.Request> requests = new ArrayList<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), new StreamingTransport(requests, 200,
                Map.of("Content-Type", "video/mp4"), 4096));
        var provider = new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", "");
        Path staging = tempDir.resolve("video.part");

        var poll = adapter.pollVideo(provider, "remote-1");
        var download = adapter.downloadVideo(provider, "remote-1", staging, 10_000);

        assertEquals(OpenAiCompatibleMediaAdapter.VideoState.SUCCEEDED, poll.state());
        assertEquals(100, poll.progress());
        assertEquals(4096, download.bytesWritten());
        assertEquals(4096, Files.size(staging));
        assertEquals("https://api.openai.com/v1/videos/remote-1", requests.get(0).url());
        assertEquals("https://api.openai.com/v1/videos/remote-1/content", requests.get(1).url());
        assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () -> adapter.pollVideo(provider, "../escape"));
    }

    @Test
    void acceptsBinaryVideoMimeAndMissingMime(@TempDir Path tempDir) throws Exception {
        var binary = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), new StreamingTransport(new ArrayList<>(), 200, Map.of("Content-Type", "application/octet-stream"), 4096))
                .downloadVideo(provider(), "remote-1", tempDir.resolve("binary.part"), 10_000);
        assertEquals(4096, binary.bytesWritten());
        var missing = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), new StreamingTransport(new ArrayList<>(), 200, Map.of(), 4096))
                .downloadVideo(provider(), "remote-1", tempDir.resolve("missing.part"), 10_000);
        assertEquals(4096, missing.bytesWritten());
    }

    @Test
    void downloadMapsHttpFailureAndCleansStaging(@TempDir Path tempDir) throws Exception {
        Path staging = tempDir.resolve("video.part");
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), new StreamingTransport(new ArrayList<>(), 429, Map.of(), 0));

        var error = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                adapter.downloadVideo(provider(), "remote-1", staging, 10_000));

        assertEquals("RATE_LIMITED", error.code());
        assertFalse(Files.exists(staging));
    }

    @Test
    void downloadRejectsInvalidMimeSmallFileAndOversizeWithStableCodes(@TempDir Path tempDir) throws Exception {
        var invalidMime = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                new OpenAiCompatibleMediaAdapter(new ObjectMapper(), new StreamingTransport(new ArrayList<>(), 200, Map.of("Content-Type", "text/html"), 4096))
                        .downloadVideo(provider(), "remote-1", tempDir.resolve("mime.part"), 10_000));
        assertEquals("DOWNLOAD_CONTENT_TYPE_INVALID", invalidMime.code());
        assertFalse(Files.exists(tempDir.resolve("mime.part")));

        var tooSmall = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                new OpenAiCompatibleMediaAdapter(new ObjectMapper(), new StreamingTransport(new ArrayList<>(), 200, Map.of("Content-Type", "video/mp4"), 1024))
                        .downloadVideo(provider(), "remote-1", tempDir.resolve("small.part"), 10_000));
        assertEquals("DOWNLOAD_FILE_TOO_SMALL", tooSmall.code());
        assertFalse(Files.exists(tempDir.resolve("small.part")));

        var oversized = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                new OpenAiCompatibleMediaAdapter(new ObjectMapper(), new StreamingTransport(new ArrayList<>(), 200, Map.of("Content-Type", "video/mp4"), 4096))
                        .downloadVideo(provider(), "remote-1", tempDir.resolve("large.part"), 2048));
        assertEquals("DOWNLOAD_SIZE_EXCEEDED", oversized.code());
        assertFalse(Files.exists(tempDir.resolve("large.part")));
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
    void dashScopeVoiceUsesNativeHttpBodyAndAcceptsBase64Audio() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            String encoded = java.util.Base64.getEncoder().encodeToString(new byte[2048]);
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    ("{\"output\":{\"audio\":{\"data\":\"" + encoded + "\"}}}").getBytes(StandardCharsets.UTF_8));
        });

        var result = adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://dashscope.aliyuncs.com", "secret", "", "", "/api/v1/services/aigc/multimodal-generation/generation", "dashscope_tts_http"),
                "你好，猫作", "qwen3-tts-flash", "Cherry", "不要把说明字段发送给供应商");

        assertEquals(2048, result.bytes().length);
        String body = new String(captured.get().body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"text\":\"你好，猫作\""));
        assertTrue(body.contains("\"voice\":\"Cherry\""));
        assertFalse(body.contains("instructions"));
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", captured.get().url());
    }

    @Test
    void miniMaxVoiceUsesModelSpecificBodyAndAcceptsHexAudio() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    ("{\"output\":{\"base_resp\":{\"status_code\":0},\"audio\":\"" + "ff".repeat(2048) + "\"}}")
                            .getBytes(StandardCharsets.UTF_8));
        });

        var result = adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://dashscope.aliyuncs.com", "secret", "", "",
                        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                        "dashscope_minimax_tts_http"),
                "你好，猫作", "MiniMax/speech-2.8-hd", "male-qn-qingse", "");

        assertEquals(2048, result.bytes().length);
        var body = new ObjectMapper().readTree(captured.get().body());
        assertEquals("male-qn-qingse", body.path("input").path("voice_setting").path("voice_id").asText());
        assertEquals("mp3", body.path("input").path("audio_setting").path("format").asText());
        assertEquals(32000, body.path("input").path("audio_setting").path("sample_rate").asInt());
        assertEquals(128000, body.path("input").path("audio_setting").path("bitrate").asInt());
        assertEquals(1, body.path("input").path("audio_setting").path("channel").asInt());
        assertTrue(body.path("input").path("output_format").isMissingNode());
        assertTrue(body.path("input").path("language_type").isMissingNode());
    }

    @Test
    void voiceProtocolReplacesIncompatibleDefaultsBeforeBuildingRequestBody() throws Exception {
        List<MediaHttpTransport.Request> captured = new ArrayList<>();
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[2048]);
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.add(request);
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    ("{\"output\":{\"audio\":{\"data\":\"" + encoded + "\"}}}").getBytes(StandardCharsets.UTF_8));
        });

        adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://dashscope.aliyuncs.com", "secret", "", "",
                        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                        "dashscope_minimax_tts_http"),
                "hello", "custom-minimax-model", "coral", "");
        adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://dashscope.aliyuncs.com", "secret", "", "",
                        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                        "dashscope_tts_http"),
                "hello", "custom-qwen-model", "coral", "");

        var miniMaxBody = new ObjectMapper().readTree(captured.get(0).body());
        var qwenBody = new ObjectMapper().readTree(captured.get(1).body());
        assertEquals("male-qn-qingse", miniMaxBody.path("input").path("voice_setting").path("voice_id").asText());
        assertEquals("Cherry", qwenBody.path("input").path("voice").asText());
    }

    @Test
    void openAiVoiceReplacesProviderSpecificDefaultButKeepsCustomVoiceIds() {
        assertEquals("coral", OpenAiCompatibleMediaAdapter.normalizeVoiceForProtocol("openai_audio_speech", "gpt-4o-mini-tts", ""));
        assertEquals("male-qn-qingse", OpenAiCompatibleMediaAdapter.normalizeVoiceForProtocol("openai_audio_speech", "speech-2.8-hd", "coral"));
        assertEquals("", OpenAiCompatibleMediaAdapter.normalizeVoiceForProtocol("openai_audio_speech", "custom-voice-model", ""));
        assertEquals("male-qn-qingse", OpenAiCompatibleMediaAdapter.normalizeVoiceForProtocol("openai_audio_speech", "male-qn-qingse"));
        assertEquals("cloned_voice_42", OpenAiCompatibleMediaAdapter.normalizeVoiceForProtocol("openai_audio_speech", "cloned_voice_42"));
        assertEquals("cloned_voice_42", OpenAiCompatibleMediaAdapter.normalizeVoiceForProtocol("dashscope_minimax_tts_http", "cloned_voice_42"));
        assertEquals("", OpenAiCompatibleMediaAdapter.normalizeVoiceForProtocol("custom_voice_http", "custom-voice-model", ""));
        assertEquals("voice_vendor_7", OpenAiCompatibleMediaAdapter.normalizeVoiceForProtocol("custom_voice_http", "custom-voice-model", "voice_vendor_7"));
    }

    @Test
    void openAiVoiceUsesOpenAiBodyAfterSwitchingFromMiniMaxVoice() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[2048]);
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    ("{\"audio\":{\"base64\":\"" + encoded + "\"}}").getBytes(StandardCharsets.UTF_8));
        });

        adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://api.openai.com", "secret", "", "", "/v1/audio/speech", "openai_audio_speech"),
                "hello", "gpt-4o-mini-tts", "male-qn-qingse", "calm");

        var body = new ObjectMapper().readTree(captured.get().body());
        assertEquals("gpt-4o-mini-tts", body.path("model").asText());
        assertEquals("hello", body.path("input").asText());
        assertEquals("coral", body.path("voice").asText());
        assertEquals("mp3", body.path("response_format").asText());
        assertEquals("calm", body.path("instructions").asText());
    }

    @Test
    void genericGatewayMiniMaxModelDoesNotReuseCoralInOpenAiBody() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[2048]);
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    ("{\"audio\":{\"base64\":\"" + encoded + "\"}}").getBytes(StandardCharsets.UTF_8));
        });

        adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://example.com/v1", "secret", "", "", "/v1/audio/speech", "openai_audio_speech"),
                "hello", "MiniMax/speech-2.8-hd", "coral", "");

        var body = new ObjectMapper().readTree(captured.get().body());
        assertEquals("male-qn-qingse", body.path("voice").asText());
    }

    @Test
    void genericGatewayQwenModelDoesNotReuseCoralInOpenAiBody() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[2048]);
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    ("{\"audio\":{\"base64\":\"" + encoded + "\"}}").getBytes(StandardCharsets.UTF_8));
        });

        adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://example.com/v1", "secret", "", "", "/v1/audio/speech", "openai_audio_speech"),
                "hello", "qwen3-tts-flash", "coral", "");

        var body = new ObjectMapper().readTree(captured.get().body());
        assertEquals("Cherry", body.path("voice").asText());
    }

    @Test
    void unknownGatewayDoesNotInjectCoralIntoCustomVoiceModel() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[2048]);
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    ("{\"audio\":{\"base64\":\"" + encoded + "\"}}").getBytes(StandardCharsets.UTF_8));
        });

        adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        "https://example.com/v1", "secret", "", "", "/v1/audio/speech", "openai_audio_speech"),
                "hello", "custom-voice-model", "", "");

        var body = new ObjectMapper().readTree(captured.get().body());
        assertTrue(body.path("voice").isMissingNode());
    }

    @Test
    void miniMaxVoiceSurfacesBusinessErrorInsideHttp200() {
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request ->
                new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                        "{\"output\":{\"base_resp\":{\"status_code\":1008,\"status_msg\":\"voice_id required\"}}}"
                                .getBytes(StandardCharsets.UTF_8)));

        var error = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                                "https://dashscope.aliyuncs.com", "secret", "", "",
                                "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                                "dashscope_minimax_tts_http"),
                        "hello", "MiniMax/speech-2.8-hd", "male-qn-qingse", ""));

        assertEquals("REMOTE_BUSINESS_ERROR", error.code());
        assertTrue(error.getMessage().contains("1008"));
    }

    @Test
    void miniMaxHttp400SurfacesSanitizedSupplierDetail() {
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request ->
                new MediaHttpTransport.Response(400, Map.of("Content-Type", "application/json"),
                        "{\"code\":\"InvalidParameter\",\"message\":\"voice_id is not supported; token=sk-secret-value\"}"
                                .getBytes(StandardCharsets.UTF_8)));

        var error = assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                                "https://ws-example.cn-beijing.maas.aliyuncs.com", "secret", "", "",
                                "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                                "dashscope_minimax_tts_http"),
                        "hello", "MiniMax/speech-2.8-hd", "male-qn-qingse", ""));

        assertEquals("REMOTE_HTTP_ERROR", error.code());
        assertTrue(error.getMessage().contains("HTTP 400"));
        assertTrue(error.getMessage().contains("voice_id is not supported"));
        assertFalse(error.getMessage().contains("sk-secret-value"));
    }

    @Test
    void dashScopeVideoUsesAsyncTaskContract() throws Exception {
        AtomicReference<MediaHttpTransport.Request> captured = new AtomicReference<>();
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request -> {
            captured.set(request);
            return new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                    "{\"output\":{\"task_id\":\"task-1\"}}".getBytes(StandardCharsets.UTF_8));
        });
        var provider = new OpenAiCompatibleMediaAdapter.ProviderContext(
                "https://dashscope.aliyuncs.com", "secret", "",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis", "",
                "openai_audio_speech", "openai_image_generation", "dashscope_video_task_http");

        var result = adapter.submitVideo(provider, "一只猫在工作", "wan2.6-t2v", "1280x720", 4);

        assertEquals("task-1", result.remoteTaskId());
        assertEquals("enable", captured.get().headers().get("X-DashScope-Async"));
        assertTrue(new String(captured.get().body(), StandardCharsets.UTF_8).contains("\"size\":\"1280*720\""));
    }

    @Test
    void openAiVoiceAcceptsNestedBase64JsonResponse() throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[2048]);
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(new ObjectMapper(), request ->
                new MediaHttpTransport.Response(200, Map.of("Content-Type", "application/json"),
                        ("{\"result\":{\"audio\":{\"base64\":\"data:audio/mpeg;base64," + encoded + "\"}}}").getBytes(StandardCharsets.UTF_8)));

        var result = adapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", ""),
                "hello", "voice-model", "coral", "");

        assertEquals(2048, result.bytes().length);
    }

    @Test
    void rejectsHttpMediaEndpointBeforeTransport() {
        assertThrows(OpenAiCompatibleMediaAdapter.MediaAdapterException.class, () ->
                new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", "http://api.openai.com/tts"));
    }

    private OpenAiCompatibleMediaAdapter.ProviderContext provider() {
        return new OpenAiCompatibleMediaAdapter.ProviderContext("https://api.openai.com", "secret", "");
    }

    private static class StreamingTransport implements MediaHttpTransport {
        private final List<Request> requests;
        private final int status;
        private final Map<String, String> headers;
        private final int bytes;

        private StreamingTransport(List<Request> requests, int status, Map<String, String> headers, int bytes) {
            this.requests = requests;
            this.status = status;
            this.headers = headers;
            this.bytes = bytes;
        }

        @Override
        public Response execute(Request request) {
            requests.add(request);
            return new Response(200, Map.of(), "{\"status\":\"completed\",\"progress\":100}".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public DownloadResponse download(Request request, Path staging, long maxBytes) throws Exception {
            requests.add(request);
            if (status < 200 || status >= 300) return new DownloadResponse(status, headers, 0);
            if (bytes > maxBytes) throw new DownloadLimitExceededException(maxBytes);
            Files.write(staging, new byte[bytes]);
            return new DownloadResponse(status, headers, bytes);
        }
    }
}
