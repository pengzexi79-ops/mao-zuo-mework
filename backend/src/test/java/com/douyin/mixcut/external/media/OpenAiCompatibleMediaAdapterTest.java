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
