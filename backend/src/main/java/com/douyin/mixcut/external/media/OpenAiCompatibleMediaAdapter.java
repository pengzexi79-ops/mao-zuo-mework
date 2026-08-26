package com.douyin.mixcut.external.media;

import com.douyin.mixcut.security.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed OpenAI-compatible media request contracts, isolated from task orchestration and material import. */
@Component
public class OpenAiCompatibleMediaAdapter implements MediaAdapter {
    private final ObjectMapper json;

    @Override
    public boolean supportsProtocol(String protocol) {
        return MediaAdapterRegistry.OPENAI_IMAGE_GENERATION.equals(protocol)
                || MediaAdapterRegistry.OPENAI_VIDEO_GENERATION.equals(protocol)
                || MediaAdapterRegistry.OPENAI_AUDIO_SPEECH.equals(protocol);
    }
    private final MediaHttpTransport transport;

    public OpenAiCompatibleMediaAdapter(ObjectMapper json, MediaHttpTransport transport) {
        this.json = json;
        this.transport = transport;
    }

    public ImageSubmission submitImage(ProviderContext provider, String prompt, String model, String size, String quality) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", size);
        body.put("quality", quality);
        body.put("n", 1);
        MediaHttpTransport.Response response = executeJson(provider, provider.imagePath(), body);
        JsonNode payload = readSuccess(response, "图片生成");
        JsonNode first = payload.path("data").path(0);
        String base64 = first.path("b64_json").asText("");
        String url = first.path("url").asText("");
        if (base64.isBlank() && url.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "供应商未返回图片数据");
        return new ImageSubmission(base64, url);
    }

    public VideoSubmission submitVideo(ProviderContext provider, String prompt, String model, String size, int seconds) throws Exception {
        String boundary = "----mework-adapter-" + java.util.UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n" + prompt + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"size\"\r\n\r\n" + size + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"seconds\"\r\n\r\n" + seconds + "\r\n--" + boundary + "--\r\n";
        String url = endpoint(provider.baseUrl(), provider.videoPath());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + provider.apiKey());
        MediaHttpTransport.Response response = transport.execute(new MediaHttpTransport.Request("POST", url, headers,
                body.getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=" + boundary));
        JsonNode payload = readSuccess(response, "视频生成");
        String remoteTaskId = payload.path("id").asText("");
        if (remoteTaskId.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "供应商未返回视频任务 ID");
        return new VideoSubmission(remoteTaskId);
    }

    public VideoPoll pollVideo(ProviderContext provider, String remoteTaskId) throws Exception {
        String safeId = remoteTaskId(remoteTaskId);
        MediaHttpTransport.Response response = executeGet(provider, append(provider.videoPath(), safeId));
        JsonNode payload = readSuccess(response, "视频状态查询");
        String rawStatus = payload.path("status").asText("").toLowerCase(java.util.Locale.ROOT);
        VideoState state = switch (rawStatus) {
            case "queued", "pending" -> VideoState.QUEUED;
            case "running", "processing", "in_progress" -> VideoState.RUNNING;
            case "completed", "succeeded" -> VideoState.SUCCEEDED;
            case "failed" -> VideoState.FAILED;
            case "cancelled", "canceled" -> VideoState.CANCELLED;
            case "expired" -> VideoState.EXPIRED;
            default -> VideoState.UNKNOWN;
        };
        int progress = Math.max(0, Math.min(100, payload.path("progress").asInt(0)));
        return new VideoPoll(state, progress, payload.path("error").asText(""));
    }

    public VideoDownload downloadVideo(ProviderContext provider, String remoteTaskId, Path staging, long maxBytes) throws Exception {
        try {
            String safeId = remoteTaskId(remoteTaskId);
            String url = endpoint(provider.baseUrl(), append(provider.videoPath(), safeId, "content"));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + provider.apiKey());
            MediaHttpTransport.DownloadResponse response = transport.download(
                    new MediaHttpTransport.Request("GET", url, headers, new byte[0], ""), staging, maxBytes);
            if (response.status() < 200 || response.status() >= 300) throw failure(response.status(), "视频下载");
            String contentType = header(response.headers(), "content-type");
            String normalizedType = contentType.toLowerCase(java.util.Locale.ROOT);
            if (!contentType.isBlank() && !normalizedType.startsWith("video/") && !"application/octet-stream".equals(normalizedType)) {
                throw new MediaAdapterException("DOWNLOAD_CONTENT_TYPE_INVALID", "供应商视频响应类型无效");
            }
            if (response.bytesWritten() < 2048 || !Files.isRegularFile(staging) || Files.size(staging) < 2048) {
                throw new MediaAdapterException("DOWNLOAD_FILE_TOO_SMALL", "供应商返回的视频文件无效");
            }
            return new VideoDownload(response.bytesWritten(), contentType);
        } catch (MediaHttpTransport.DownloadLimitExceededException error) {
            deleteStaging(staging);
            throw new MediaAdapterException("DOWNLOAD_SIZE_EXCEEDED", error.getMessage());
        } catch (Exception error) {
            deleteStaging(staging);
            throw error;
        }
    }

    public VoiceSubmission submitVoice(ProviderContext provider, String prompt, String model, String voice, String instructions) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        body.put("input", prompt);
        body.put("voice", voice);
        body.put("response_format", "mp3");
        if (instructions != null && !instructions.isBlank()) body.put("instructions", instructions.substring(0, Math.min(1000, instructions.length())));
        MediaHttpTransport.Response response = executeJson(provider, provider.voicePath(), body);
        if (response.status() < 200 || response.status() >= 300) throw failure(response.status(), "配音生成");
        if (response.body() == null || response.body().length < 1024) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "供应商返回的配音文件无效");
        return new VoiceSubmission(response.body(), header(response, "content-type"));
    }

    private MediaHttpTransport.Response executeGet(ProviderContext provider, String path) throws Exception {
        String url = endpoint(provider.baseUrl(), path);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + provider.apiKey());
        return transport.execute(new MediaHttpTransport.Request("GET", url, headers, new byte[0], ""));
    }

    private MediaHttpTransport.Response executeJson(ProviderContext provider, String path, JsonNode body) throws Exception {
        String url = endpoint(provider.baseUrl(), path);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + provider.apiKey());
        headers.put("Accept", "application/json");
        return transport.execute(new MediaHttpTransport.Request("POST", url, headers,
                json.writeValueAsBytes(body), "application/json"));
    }

    private JsonNode readSuccess(MediaHttpTransport.Response response, String action) throws Exception {
        if (response.status() < 200 || response.status() >= 300) throw failure(response.status(), action);
        return json.readTree(response.body() == null ? new byte[0] : response.body());
    }

    private String endpoint(String baseUrl, String path) {
        String base = UrlGuard.validate(baseUrl).replaceAll("/+$", "");
        URI uri = URI.create(base);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new MediaAdapterException("MEDIA_PROTOCOL_UNSUPPORTED", "付费 AI Provider 必须使用 HTTPS 地址");
        String route = path == null || path.isBlank() ? "/v1/images/generations" : path.trim();
        if (route.startsWith("https://")) return UrlGuard.validate(route);
        if (!route.startsWith("/") || route.contains("..") || route.contains("?") || route.contains("#")) {
            throw new MediaAdapterException("MEDIA_PROTOCOL_UNSUPPORTED", "OpenAI-compatible 媒体路径无效");
        }
        if (base.endsWith("/api/v3") && route.startsWith("/v1/")) return UrlGuard.validate(base + route.substring(3));
        return UrlGuard.validate(base + route);
    }

    private String append(String path, String... parts) {
        String base = path == null || path.isBlank() ? "/v1/videos" : path.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        StringBuilder result = new StringBuilder(base);
        for (String part : parts) result.append('/').append(part);
        return result.toString();
    }

    private String remoteTaskId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[A-Za-z0-9._:-]{1,255}")) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "远端视频任务 ID 无效");
        return value;
    }

    private MediaAdapterException failure(int status, String action) {
        String code = status == 401 || status == 403 ? "AUTH_REQUIRED"
                : status == 429 ? "RATE_LIMITED"
                : status >= 500 ? "REMOTE_SERVER_ERROR" : "REMOTE_HTTP_ERROR";
        return new MediaAdapterException(code, action + "失败（HTTP " + status + "）");
    }

    private String header(MediaHttpTransport.Response response, String name) { return header(response.headers(), name); }

    private String header(Map<String, String> headers, String name) {
        if (headers == null) return "";
        return headers.entrySet().stream().filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }

    private void deleteStaging(Path staging) {
        try { Files.deleteIfExists(staging); } catch (Exception ignored) { }
    }

    public record ProviderContext(String baseUrl, String apiKey, String imagePath, String videoPath, String voicePath) {
        public ProviderContext(String baseUrl, String apiKey, String voicePath) {
            this(baseUrl, apiKey, "", "", voicePath);
        }
        public ProviderContext {
            if (imagePath == null || imagePath.isBlank()) imagePath = "/v1/images/generations";
            if (videoPath == null || videoPath.isBlank()) videoPath = "/v1/videos";
            if (voicePath == null || voicePath.isBlank()) voicePath = "/v1/audio/speech";
            if (!validPath(imagePath) || !validPath(videoPath) || !validPath(voicePath)) throw new MediaAdapterException("MEDIA_PROTOCOL_UNSUPPORTED", "OpenAI-compatible 媒体 endpoint 无效");
        }
        private static boolean validPath(String value) { return value.startsWith("/") || value.startsWith("https://"); }
    }
    public enum VideoState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED, UNKNOWN }
    public record ImageSubmission(String base64, String url) {}
    public record VideoSubmission(String remoteTaskId) {}
    public record VideoPoll(VideoState state, int progress, String error) {}
    public record VideoDownload(long bytesWritten, String contentType) {}
    public record VoiceSubmission(byte[] bytes, String contentType) {}

    public static class MediaAdapterException extends IllegalStateException {
        private final String code;
        public MediaAdapterException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
