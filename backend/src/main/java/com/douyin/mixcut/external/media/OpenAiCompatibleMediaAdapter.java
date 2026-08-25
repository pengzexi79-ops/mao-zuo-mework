package com.douyin.mixcut.external.media;

import com.douyin.mixcut.security.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed OpenAI-compatible media request contracts, isolated from task orchestration and material import. */
@Component
public class OpenAiCompatibleMediaAdapter {
    private final ObjectMapper json;
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
        MediaHttpTransport.Response response = executeJson(provider, "/v1/images/generations", body);
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
        String url = endpoint(provider.baseUrl(), "/v1/videos");
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
        MediaHttpTransport.Response response = executeGet(provider, "/v1/videos/" + safeId);
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

    public VideoDownload downloadVideo(ProviderContext provider, String remoteTaskId) throws Exception {
        MediaHttpTransport.Response response = executeGet(provider, "/v1/videos/" + remoteTaskId(remoteTaskId) + "/content");
        if (response.status() < 200 || response.status() >= 300) throw failure(response.status(), "视频下载");
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        if (bytes.length < 2048) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "供应商返回的视频文件无效");
        String contentType = header(response, "content-type");
        if (!contentType.isBlank() && !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("video/")) {
            throw new MediaAdapterException("DOWNLOAD_CONTENT_TYPE_INVALID", "供应商视频响应类型无效");
        }
        return new VideoDownload(bytes, contentType);
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
        if (!path.startsWith("/v1/")) throw new MediaAdapterException("MEDIA_PROTOCOL_UNSUPPORTED", "OpenAI-compatible 媒体路径无效");
        if (base.endsWith("/api/v3")) return UrlGuard.validate(base + path.substring(3));
        return UrlGuard.validate(base + path);
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

    private String header(MediaHttpTransport.Response response, String name) {
        if (response.headers() == null) return "";
        return response.headers().entrySet().stream().filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }

    public record ProviderContext(String baseUrl, String apiKey, String voicePath) {
        public ProviderContext {
            if (voicePath == null || voicePath.isBlank()) voicePath = "/v1/audio/speech";
            if (!voicePath.startsWith("/v1/")) throw new MediaAdapterException("MEDIA_PROTOCOL_UNSUPPORTED", "OpenAI-compatible 配音 endpoint 无效");
        }
    }
    public enum VideoState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED, UNKNOWN }
    public record ImageSubmission(String base64, String url) {}
    public record VideoSubmission(String remoteTaskId) {}
    public record VideoPoll(VideoState state, int progress, String error) {}
    public record VideoDownload(byte[] bytes, String contentType) {}
    public record VoiceSubmission(byte[] bytes, String contentType) {}

    public static class MediaAdapterException extends IllegalStateException {
        private final String code;
        public MediaAdapterException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
