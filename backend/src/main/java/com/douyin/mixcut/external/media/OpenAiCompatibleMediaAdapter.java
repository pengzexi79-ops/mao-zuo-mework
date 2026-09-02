package com.douyin.mixcut.external.media;

import com.douyin.mixcut.security.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed OpenAI-compatible media request contracts, isolated from task orchestration and material import. */
@Component
public class OpenAiCompatibleMediaAdapter implements MediaAdapter {
    private final ObjectMapper json;

    @Override
    public boolean supportsProtocol(String protocol) {
        return MediaAdapterRegistry.OPENAI_IMAGE_GENERATION.equals(protocol)
                || MediaAdapterRegistry.DASHSCOPE_IMAGE_HTTP.equals(protocol)
                || MediaAdapterRegistry.DASHSCOPE_IMAGE_TASK_HTTP.equals(protocol)
                || MediaAdapterRegistry.OPENAI_VIDEO_GENERATION.equals(protocol)
                || MediaAdapterRegistry.DASHSCOPE_VIDEO_TASK_HTTP.equals(protocol)
                || MediaAdapterRegistry.OPENAI_AUDIO_SPEECH.equals(protocol)
                || MediaAdapterRegistry.DASHSCOPE_TTS_HTTP.equals(protocol)
                || MediaAdapterRegistry.DASHSCOPE_MINIMAX_TTS_HTTP.equals(protocol);
    }
    private final MediaHttpTransport transport;

    public OpenAiCompatibleMediaAdapter(ObjectMapper json, MediaHttpTransport transport) {
        this.json = json;
        this.transport = transport;
    }

    public ImageSubmission submitImage(ProviderContext provider, String prompt, String model, String size, String quality) throws Exception {
        if (MediaAdapterRegistry.DASHSCOPE_IMAGE_TASK_HTTP.equals(provider.imageProtocol())) {
            return submitDashScopeImageTask(provider, prompt, model, size);
        }
        if (MediaAdapterRegistry.DASHSCOPE_IMAGE_HTTP.equals(provider.imageProtocol())) {
            return submitDashScopeImage(provider, prompt, model, size);
        }
        var body = json.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", size);
        body.put("quality", quality);
        body.put("n", 1);
        // Prefer an inline result. This avoids a second unauthenticated CDN
        // download when a compatible gateway supports OpenAI image fields.
        body.put("response_format", "b64_json");
        body.put("output_format", "png");
        MediaHttpTransport.Response response = executeJson(provider, provider.imagePath(), body);
        if (response.status() == 400) {
            // Some compatible gateways implement the older image contract and
            // reject optional output-format fields. Retry the same request with
            // the legacy body before surfacing the provider's 400 response.
            var legacyBody = body.deepCopy();
            legacyBody.remove("response_format");
            legacyBody.remove("output_format");
            response = executeJson(provider, provider.imagePath(), legacyBody);
        }
        requireSuccess(response, "图片生成");
        String contentType = header(response, "content-type").toLowerCase(java.util.Locale.ROOT);
        if (isBinaryMedia(contentType, "image") && response.body() != null && response.body().length > 0) {
            return new ImageSubmission(Base64.getEncoder().encodeToString(response.body()), "");
        }
        JsonNode payload = parseMediaPayload(response, "图片生成");
        String base64 = findBase64(payload);
        String url = findMediaUrl(payload);
        if (base64.isBlank() && url.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "供应商未返回图片数据");
        return new ImageSubmission(base64, url);
    }

    private ImageSubmission submitDashScopeImage(ProviderContext provider, String prompt, String model, String size) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        var message = body.putObject("input").putArray("messages").addObject();
        message.put("role", "user");
        message.putArray("content").addObject().put("text", prompt);
        var parameters = body.putObject("parameters");
        parameters.put("prompt_extend", true);
        parameters.put("n", 1);
        if (size != null && !size.isBlank() && !"auto".equalsIgnoreCase(size)) {
            parameters.put("size", size.toLowerCase(java.util.Locale.ROOT).replace('x', '*'));
        }
        MediaHttpTransport.Response response = executeJson(provider, provider.imagePath(), body);
        requireSuccess(response, "图片生成");
        JsonNode payload = parseMediaPayload(response, "图片生成");
        requireBusinessSuccess(payload, "图片生成");
        String base64 = findBase64(payload);
        String url = findMediaUrl(payload);
        if (base64.isBlank() && url.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "供应商未返回图片数据");
        return new ImageSubmission(base64, url);
    }

    private ImageSubmission submitDashScopeImageTask(ProviderContext provider, String prompt, String model, String size) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        body.putObject("input").put("prompt", prompt);
        var parameters = body.putObject("parameters");
        parameters.put("prompt_extend", true);
        parameters.put("watermark", false);
        parameters.put("n", 1);
        if (size != null && !size.isBlank() && !"auto".equalsIgnoreCase(size)) {
            parameters.put("size", size.toLowerCase(java.util.Locale.ROOT).replace('x', '*'));
        }
        MediaHttpTransport.Response response = executeJson(provider, provider.imagePath(), body,
                Map.of("X-DashScope-Async", "enable"));
        JsonNode payload = readSuccess(response, "DashScope 图片生成");
        requireBusinessSuccess(payload, "DashScope 图片生成");
        String taskId = firstTextDeep(payload.path("output"), "task_id", "taskId", "id");
        if (taskId.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "DashScope 未返回图片任务 ID");

        for (int attempt = 0; attempt < 150; attempt++) {
            response = executeGet(provider, dashScopeTaskEndpoint(provider, taskId, provider.imagePath()));
            payload = readSuccess(response, "DashScope 图片状态查询");
            requireBusinessSuccess(payload, "DashScope 图片状态查询");
            JsonNode output = payload.path("output");
            String status = firstText(output, "task_status", "status").toLowerCase(java.util.Locale.ROOT);
            String base64 = findBase64(output);
            String url = findMediaUrl(output);
            if (List.of("succeeded", "completed", "success").contains(status)
                    || (status.isBlank() && (!base64.isBlank() || !url.isBlank()))) {
                if (base64.isBlank() && url.isBlank()) {
                    throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "DashScope 图片任务成功但未返回图片数据");
                }
                return new ImageSubmission(base64, url);
            }
            if (List.of("failed", "cancelled", "canceled", "expired").contains(status)) {
                String detail = firstTextDeep(output, "message", "error", "code");
                throw new MediaAdapterException("REMOTE_BUSINESS_ERROR",
                        "DashScope 图片任务失败" + (detail.isBlank() ? "" : "：" + detail.substring(0, Math.min(200, detail.length()))));
            }
            if (attempt < 149) {
                try { Thread.sleep(2000); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new MediaAdapterException("MEDIA_REQUEST_CANCELLED", "图片生成等待已取消");
                }
            }
        }
        throw new MediaAdapterException("MEDIA_GENERATION_TIMEOUT", "DashScope 图片生成超过 5 分钟仍未完成");
    }

    public VideoSubmission submitVideo(ProviderContext provider, String prompt, String model, String size, int seconds) throws Exception {
        if (MediaAdapterRegistry.DASHSCOPE_VIDEO_TASK_HTTP.equals(provider.videoProtocol())) {
            return submitDashScopeVideo(provider, prompt, model, size, seconds);
        }
        String boundary = "----mework-adapter-" + java.util.UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n" + prompt + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"size\"\r\n\r\n" + size + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"seconds\"\r\n\r\n" + seconds + "\r\n--" + boundary + "--\r\n";
        String endpointUrl = endpoint(provider.baseUrl(), provider.videoPath());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + provider.apiKey());
        MediaHttpTransport.Response response = transport.execute(new MediaHttpTransport.Request("POST", endpointUrl, headers,
                body.getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=" + boundary));
        requireSuccess(response, "视频生成");
        String contentType = header(response, "content-type").toLowerCase(java.util.Locale.ROOT);
        if (isBinaryMedia(contentType, "video") && response.body() != null && response.body().length > 0) {
            return new VideoSubmission("", Base64.getEncoder().encodeToString(response.body()), "", contentType);
        }
        JsonNode payload = parseMediaPayload(response, "视频生成");
        String remoteTaskId = firstTextDeep(payload, "id", "task_id", "taskId", "request_id", "requestId");
        if (!remoteTaskId.isBlank()) return new VideoSubmission(remoteTaskId);
        String base64 = findBase64(payload);
        String url = findMediaUrl(payload);
        if (base64.isBlank() && url.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "供应商未返回视频任务 ID、视频 Base64 或视频地址");
        return new VideoSubmission("", base64, url, "video/mp4");
    }

    public VideoPoll pollVideo(ProviderContext provider, String remoteTaskId) throws Exception {
        if (MediaAdapterRegistry.DASHSCOPE_VIDEO_TASK_HTTP.equals(provider.videoProtocol())) {
            return pollDashScopeVideo(provider, remoteTaskId);
        }
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
        if (MediaAdapterRegistry.DASHSCOPE_VIDEO_TASK_HTTP.equals(provider.videoProtocol())) {
            return downloadDashScopeVideo(provider, remoteTaskId, staging, maxBytes);
        }
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

    private VideoSubmission submitDashScopeVideo(ProviderContext provider, String prompt, String model,
                                                  String size, int seconds) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        body.putObject("input").put("prompt", prompt);
        var parameters = body.putObject("parameters");
        parameters.put("size", size.toLowerCase(java.util.Locale.ROOT).replace('x', '*'));
        parameters.put("duration", seconds);
        parameters.put("prompt_extend", true);
        parameters.put("watermark", false);
        MediaHttpTransport.Response response = executeJson(provider, provider.videoPath(), body,
                Map.of("X-DashScope-Async", "enable"));
        requireSuccess(response, "视频生成");
        JsonNode payload = parseMediaPayload(response, "DashScope 视频生成");
        requireBusinessSuccess(payload, "DashScope 视频生成");
        String taskId = firstTextDeep(payload.path("output"), "task_id", "taskId", "id");
        if (taskId.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "DashScope 未返回视频任务 ID");
        return new VideoSubmission(taskId);
    }

    private VideoPoll pollDashScopeVideo(ProviderContext provider, String remoteTaskId) throws Exception {
        MediaHttpTransport.Response response = executeGet(provider, dashScopeTaskEndpoint(provider, remoteTaskId, provider.videoPath()));
        JsonNode payload = readSuccess(response, "DashScope 视频状态查询");
        requireBusinessSuccess(payload, "DashScope 视频状态查询");
        JsonNode output = payload.path("output");
        String rawStatus = firstText(output, "task_status", "status").toLowerCase(java.util.Locale.ROOT);
        VideoState state = switch (rawStatus) {
            case "pending", "queued" -> VideoState.QUEUED;
            case "running", "processing" -> VideoState.RUNNING;
            case "succeeded", "completed", "success" -> VideoState.SUCCEEDED;
            case "failed" -> VideoState.FAILED;
            case "cancelled", "canceled" -> VideoState.CANCELLED;
            case "expired" -> VideoState.EXPIRED;
            default -> VideoState.UNKNOWN;
        };
        int progress = Math.max(0, Math.min(100, firstIntDeep(output, "progress", "progress_percent")));
        return new VideoPoll(state, progress, firstTextDeep(output, "message", "error", "code"));
    }

    private VideoDownload downloadDashScopeVideo(ProviderContext provider, String remoteTaskId,
                                                   Path staging, long maxBytes) throws Exception {
        try {
            MediaHttpTransport.Response statusResponse = executeGet(provider, dashScopeTaskEndpoint(provider, remoteTaskId, provider.videoPath()));
            JsonNode payload = readSuccess(statusResponse, "DashScope 视频结果查询");
            requireBusinessSuccess(payload, "DashScope 视频结果查询");
            String url = findMediaUrl(payload.path("output"));
            if (url.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "DashScope 未返回视频下载地址");
            MediaHttpTransport.DownloadResponse response = transport.download(
                    new MediaHttpTransport.Request("GET", UrlGuard.validate(url), Map.of("Accept", "video/*,application/octet-stream,*/*"), new byte[0], ""),
                    staging, maxBytes);
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
        voice = normalizeVoiceForProtocol(provider.voiceProtocol(), model, voice, isOfficialOpenAiBase(provider.baseUrl()));
        if (MediaAdapterRegistry.DASHSCOPE_MINIMAX_TTS_HTTP.equals(provider.voiceProtocol())) {
            return submitDashScopeMiniMaxVoice(provider, prompt, model, voice);
        }
        if ("dashscope_tts_http".equals(provider.voiceProtocol())) {
            return submitDashScopeVoice(provider, prompt, model, voice, instructions);
        }
        var body = json.createObjectNode();
        body.put("model", model);
        body.put("input", prompt);
        if (!voice.isBlank()) body.put("voice", voice);
        body.put("response_format", "mp3");
        if (instructions != null && !instructions.isBlank()) body.put("instructions", instructions.substring(0, Math.min(1000, instructions.length())));
        MediaHttpTransport.Response response = executeJson(provider, provider.voicePath(), body);
        if (response.status() < 200 || response.status() >= 300) throw failure(response, "配音生成");
        String contentType = header(response, "content-type").toLowerCase(java.util.Locale.ROOT);
        if (isBinaryMedia(contentType, "audio") && response.body() != null && response.body().length > 0) {
            return new VoiceSubmission(response.body(), contentType);
        }
        JsonNode payload = parseMediaPayload(response, "配音生成");
        String base64 = findBase64(payload);
        if (!base64.isBlank()) return new VoiceSubmission(decodeBase64(base64, "供应商返回的配音 Base64 无效"), "audio/mpeg");
        String url = findMediaUrl(payload);
        if (!url.isBlank()) return downloadAudio(url);
        throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "供应商未返回配音文件、Base64 或音频地址");
    }

    public static String normalizeVoiceForProtocol(String protocol, String requested) {
        return normalizeVoiceForProtocol(protocol, "", requested);
    }

    /**
     * Normalizes the voice id for the selected wire protocol without losing the
     * model family when a gateway exposes that model through an OpenAI-shaped API.
     */
    public static String normalizeVoiceForProtocol(String protocol, String model, String requested) {
        return normalizeVoiceForProtocol(protocol, model, requested, false);
    }

    public static String normalizeVoiceForProtocol(String protocol, String model, String requested, boolean officialOpenAi) {
        String value = requested == null ? "" : requested.trim();
        if (!value.matches("[A-Za-z0-9._:/-]{1,80}")) value = "";
        List<String> openAiVoices = List.of("alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer");
        List<String> qwenVoices = List.of("Cherry", "longanhuan_v3.6");
        String miniMaxVoice = "male-qn-qingse";
        if (MediaAdapterRegistry.DASHSCOPE_MINIMAX_TTS_HTTP.equals(protocol)) {
            return value.isBlank() || containsIgnoreCase(openAiVoices, value) || containsIgnoreCase(qwenVoices, value)
                    ? miniMaxVoice : value;
        }
        if (MediaAdapterRegistry.DASHSCOPE_TTS_HTTP.equals(protocol)) {
            return value.isBlank() || containsIgnoreCase(openAiVoices, value) || miniMaxVoice.equalsIgnoreCase(value)
                    ? qwenVoices.get(0) : value;
        }
        if (MediaAdapterRegistry.OPENAI_AUDIO_SPEECH.equals(protocol)) {
            String modelFamily = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
            if (isMiniMaxTtsModel(modelFamily)
                    && (value.isBlank() || containsIgnoreCase(openAiVoices, value) || containsIgnoreCase(qwenVoices, value))) {
                return miniMaxVoice;
            }
            if (isQwenTtsModel(modelFamily)
                    && (value.isBlank() || containsIgnoreCase(openAiVoices, value) || miniMaxVoice.equalsIgnoreCase(value))) {
                return qwenVoices.get(0);
            }
            if (officialOpenAi || isOpenAiTtsModel(modelFamily)) {
                return value.isBlank() || containsIgnoreCase(qwenVoices, value) || miniMaxVoice.equalsIgnoreCase(value)
                        ? "coral" : value;
            }
            return value;
        }
        return value;
    }

    private static boolean isQwenTtsModel(String model) {
        return model.contains("qwen3-tts") || model.contains("qwen-tts");
    }

    private static boolean isMiniMaxTtsModel(String model) {
        return (model.contains("minimax")
                && (model.contains("speech") || model.contains("tts") || model.contains("voice")))
                || model.matches(".*(^|[/_:.-])speech-(?:0?[12]|2(?:\\.\\d+)?)(?:[_.-]|$).*");
    }

    private static boolean isOpenAiTtsModel(String model) {
        return model.startsWith("tts-1") || model.contains("gpt-4o-mini-tts")
                || (model.contains("openai") && model.contains("tts"));
    }

    private static boolean isOfficialOpenAiBase(String baseUrl) {
        try {
            String host = URI.create(baseUrl == null ? "" : baseUrl.trim()).getHost();
            return host != null && host.equalsIgnoreCase("api.openai.com");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(target));
    }

    private VoiceSubmission submitDashScopeVoice(ProviderContext provider, String prompt, String model,
                                                  String voice, String instructions) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        var input = body.putObject("input");
        input.put("text", prompt);
        input.put("voice", voice);
        input.put("language_type", "Chinese");

        MediaHttpTransport.Response response = executeJson(provider, provider.voicePath(), body);
        String contentType = header(response, "content-type").toLowerCase(java.util.Locale.ROOT);
        if (response.status() >= 200 && response.status() < 300 && contentType.startsWith("audio/")) {
            return new VoiceSubmission(response.body(), contentType);
        }
        JsonNode payload = parseMediaPayload(response, "DashScope 配音");
        requireBusinessSuccess(payload, "DashScope 配音");
        JsonNode audio = payload.path("output").path("audio");
        String base64 = findBase64(audio);
        if (base64.isBlank()) base64 = findBase64(payload);
        if (!base64.isBlank()) {
            return new VoiceSubmission(decodeBase64(base64, "DashScope 返回的配音 Base64 无效"), "audio/mpeg");
        }
        String url = findMediaUrl(audio);
        if (url.isBlank()) url = findMediaUrl(payload);
        if (url.isBlank()) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "DashScope 未返回音频数据或音频地址");
        return downloadAudio(url);
    }

    private VoiceSubmission submitDashScopeMiniMaxVoice(ProviderContext provider, String prompt, String model,
                                                         String voice) throws Exception {
        var body = json.createObjectNode();
        body.put("model", model);
        var input = body.putObject("input");
        input.put("text", prompt);
        input.putObject("voice_setting").put("voice_id", voice);
        var audioSetting = input.putObject("audio_setting");
        audioSetting.put("format", "mp3");
        audioSetting.put("sample_rate", 32000);
        audioSetting.put("bitrate", 128000);
        audioSetting.put("channel", 1);
        MediaHttpTransport.Response response = executeJson(provider, provider.voicePath(), body);
        requireSuccess(response, "MiniMax 配音");
        String contentType = header(response, "content-type").toLowerCase(java.util.Locale.ROOT);
        if (isBinaryMedia(contentType, "audio") && response.body() != null && response.body().length > 0) {
            return new VoiceSubmission(response.body(), contentType);
        }
        JsonNode payload = parseMediaPayload(response, "MiniMax 配音");
        requireBusinessSuccess(payload, "MiniMax 配音");
        byte[] hex = findHexAudio(payload.path("output"));
        if (hex.length > 0) return new VoiceSubmission(hex, "audio/mpeg");
        String base64 = findBase64(payload);
        if (!base64.isBlank()) return new VoiceSubmission(decodeBase64(base64, "MiniMax 返回的配音 Base64 无效"), "audio/mpeg");
        String url = findMediaUrl(payload);
        if (!url.isBlank()) return downloadAudio(url);
        throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "MiniMax 未返回音频数据或音频地址");
    }

    private VoiceSubmission downloadAudio(String rawUrl) throws Exception {
        String url = UrlGuard.validate(rawUrl);
        Path temp = Files.createTempFile("mework-tts-", ".audio");
        try {
            MediaHttpTransport.DownloadResponse response = transport.download(
                    new MediaHttpTransport.Request("GET", url, Map.of("Accept", "audio/*,*/*"), new byte[0], ""),
                    temp, 50L * 1024 * 1024);
            if (response.status() == 401 || response.status() == 403) {
                throw new MediaAdapterException("AUTH_REQUIRED", "DashScope 音频地址被供应商拒绝（HTTP " + response.status() + "）");
            }
            if (response.status() < 200 || response.status() >= 300 || response.bytesWritten() < 1024) {
                throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "DashScope 返回的音频地址不可下载");
            }
            return new VoiceSubmission(Files.readAllBytes(temp), "audio/mpeg");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String firstTextDeep(JsonNode node, String... fields) {
        if (node == null) return "";
        if (node.isObject()) {
            for (String field : fields) {
                String value = node.path(field).asText("").trim();
                if (!value.isBlank()) return value;
            }
            var iterator = node.fields();
            while (iterator.hasNext()) {
                String value = firstTextDeep(iterator.next().getValue(), fields);
                if (!value.isBlank()) return value;
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                String found = firstTextDeep(value, fields);
                if (!found.isBlank()) return found;
            }
        }
        return "";
    }

    private int firstIntDeep(JsonNode node, String... fields) {
        String value = firstTextDeep(node, fields);
        try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; }
    }

    private byte[] findHexAudio(JsonNode node) {
        if (node == null) return new byte[0];
        if (node.isObject()) {
            for (String field : java.util.List.of("audio", "audio_data", "audioData", "data")) {
                JsonNode value = node.get(field);
                if (value != null && value.isTextual()) {
                    String raw = value.asText("").trim();
                    if (raw.length() >= 2048 && raw.length() % 2 == 0 && raw.matches("[0-9A-Fa-f]+")) {
                        byte[] decoded = new byte[raw.length() / 2];
                        for (int i = 0; i < decoded.length; i++) decoded[i] = (byte) Integer.parseInt(raw.substring(i * 2, i * 2 + 2), 16);
                        return decoded;
                    }
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                byte[] found = findHexAudio(fields.next().getValue());
                if (found.length > 0) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                byte[] found = findHexAudio(value);
                if (found.length > 0) return found;
            }
        }
        return new byte[0];
    }

    /** Gateways disagree on whether media data is b64_json, base64, data or nested output. */
    private String findBase64(JsonNode node) {
        if (node != null && node.isTextual()) return mediaScalar(node, true);
        return findMediaValue(node, true, 0);
    }

    private String findMediaUrl(JsonNode node) {
        return findMediaValue(node, false, 0);
    }

    private String findMediaValue(JsonNode node, boolean base64, int depth) {
        if (node == null || depth > 8) return "";
        if (node.isObject()) {
            java.util.List<String> priority = base64
                    ? java.util.List.of("b64_json", "base64", "image_base64", "imageBase64", "video_base64", "audio_base64", "data", "content", "result", "output", "images", "videos", "audios", "media", "url", "image_url", "video_url", "audio_url")
                    : java.util.List.of("url", "image_url", "imageUrl", "video_url", "videoUrl", "audio_url", "audioUrl", "download_url", "downloadUrl", "data", "content", "result", "output", "images", "videos", "audios", "media");
            for (String key : priority) {
                JsonNode value = node.get(key);
                String found = mediaScalar(value, base64);
                if (!found.isBlank()) return found;
                found = findMediaValue(value, base64, depth + 1);
                if (!found.isBlank()) return found;
            }
            var iterator = node.fields();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                boolean likely = base64
                        ? key.contains("base64") || key.contains("b64") || key.equals("image") || key.equals("video") || key.equals("audio")
                        : key.endsWith("url") || key.equals("uri") || key.equals("image") || key.equals("video") || key.equals("audio");
                if (!likely) {
                    if (entry.getValue().isContainerNode()) {
                        String found = findMediaValue(entry.getValue(), base64, depth + 1);
                        if (!found.isBlank()) return found;
                    }
                    continue;
                }
                String found = mediaScalar(entry.getValue(), base64);
                if (!found.isBlank()) return found;
                found = findMediaValue(entry.getValue(), base64, depth + 1);
                if (!found.isBlank()) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                String found = findMediaValue(value, base64, depth + 1);
                if (!found.isBlank()) return found;
            }
        }
        return "";
    }

    private String mediaScalar(JsonNode value, boolean base64) {
        if (value == null || !value.isTextual()) return "";
        String raw = value.asText("").trim();
        if (raw.isBlank()) return "";
        if (!base64) return raw.startsWith("https://") ? raw : "";
        if (raw.startsWith("data:")) {
            int comma = raw.indexOf(',');
            if (comma > 0) raw = raw.substring(comma + 1);
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) return "";
        String compact = raw.replaceAll("\\s+", "");
        // Unpadded Base64 may be two or three characters long (the legacy
        // adapter also accepted these values from b64_json fields).
        return compact.matches("[A-Za-z0-9+/=_-]{2,}") ? compact : "";
    }

    private byte[] decodeBase64(String value, String message) {
        String normalized = mediaScalar(json.getNodeFactory().textNode(value), true);
        try {
            return Base64.getMimeDecoder().decode(normalized);
        } catch (IllegalArgumentException error) {
            try {
                return Base64.getUrlDecoder().decode(normalized);
            } catch (IllegalArgumentException ignored) {
                throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", message);
            }
        }
    }

    private MediaHttpTransport.Response executeGet(ProviderContext provider, String path) throws Exception {
        String url = endpoint(provider.baseUrl(), path);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + provider.apiKey());
        return transport.execute(new MediaHttpTransport.Request("GET", url, headers, new byte[0], ""));
    }

    private MediaHttpTransport.Response executeJson(ProviderContext provider, String path, JsonNode body) throws Exception {
        return executeJson(provider, path, body, Map.of());
    }

    private MediaHttpTransport.Response executeJson(ProviderContext provider, String path, JsonNode body,
                                                     Map<String, String> extraHeaders) throws Exception {
        String url = endpoint(provider.baseUrl(), path);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + provider.apiKey());
        headers.put("Accept", "application/json");
        headers.putAll(extraHeaders);
        return transport.execute(new MediaHttpTransport.Request("POST", url, headers,
                json.writeValueAsBytes(body), "application/json"));
    }

    private JsonNode readSuccess(MediaHttpTransport.Response response, String action) throws Exception {
        if (response.status() < 200 || response.status() >= 300) throw failure(response, action);
        return json.readTree(response.body() == null ? new byte[0] : response.body());
    }

    private void requireSuccess(MediaHttpTransport.Response response, String action) {
        if (response.status() < 200 || response.status() >= 300) throw failure(response, action);
    }

    private void requireBusinessSuccess(JsonNode payload, String action) {
        JsonNode baseResponse = payload == null ? null : payload.path("output").path("base_resp");
        if (baseResponse == null || baseResponse.isMissingNode() || baseResponse.isNull()) return;
        String rawCode = baseResponse.path("status_code").asText("").trim();
        if (rawCode.isBlank() || "0".equals(rawCode)) return;
        String detail = firstTextDeep(baseResponse, "status_msg", "message", "msg", "error");
        if (detail.length() > 180) detail = detail.substring(0, 180);
        throw new MediaAdapterException("REMOTE_BUSINESS_ERROR",
                action + "被供应商业务层拒绝（状态码 " + rawCode + ")" + (detail.isBlank() ? "" : "：" + detail));
    }

    private JsonNode parseJson(MediaHttpTransport.Response response, String action) throws Exception {
        try {
            return json.readTree(response.body() == null ? new byte[0] : response.body());
        } catch (Exception error) {
            throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", action + "返回内容不是有效 JSON 或媒体文件");
        }
    }

    /** Some gateways return the media as a bare Base64 body instead of JSON. */
    private JsonNode parseMediaPayload(MediaHttpTransport.Response response, String action) throws Exception {
        byte[] body = response.body() == null ? new byte[0] : response.body();
        try {
            return json.readTree(body);
        } catch (Exception error) {
            String raw = new String(body, StandardCharsets.UTF_8).trim();
            if (!mediaScalar(json.getNodeFactory().textNode(raw), true).isBlank()) {
                return json.getNodeFactory().textNode(raw);
            }
            throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", action + "返回内容不是有效 JSON 或媒体文件");
        }
    }

    private boolean isBinaryMedia(String contentType, String mediaType) {
        return contentType.startsWith(mediaType + "/") || "application/octet-stream".equals(contentType);
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

    private String dashScopeTaskEndpoint(ProviderContext provider, String rawTaskId, String sourcePath) {
        String taskId = remoteTaskId(rawTaskId);
        String source = sourcePath != null && sourcePath.startsWith("https://") ? sourcePath : provider.baseUrl();
        URI uri = URI.create(UrlGuard.validate(source));
        return UrlGuard.validate(uri.getScheme() + "://" + uri.getRawAuthority() + "/api/v1/tasks/" + taskId);
    }

    private String remoteTaskId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[A-Za-z0-9._:-]{1,255}")) throw new MediaAdapterException("MEDIA_RESPONSE_INVALID", "远端任务 ID 无效");
        return value;
    }

    private MediaAdapterException failure(int status, String action) {
        return failure(status, action, "");
    }

    private MediaAdapterException failure(MediaHttpTransport.Response response, String action) {
        return failure(response.status(), action, remoteErrorDetail(response.body()));
    }

    private MediaAdapterException failure(int status, String action, String detail) {
        String code = status == 401 || status == 403 ? "AUTH_REQUIRED"
                : status == 404 ? "MEDIA_ENDPOINT_NOT_FOUND"
                : status == 429 ? "RATE_LIMITED"
                : status >= 500 ? "REMOTE_SERVER_ERROR" : "REMOTE_HTTP_ERROR";
        String message = status == 404
                ? action + "端点不存在或协议不匹配（HTTP 404）"
                : action + "失败（HTTP " + status + "）";
        return new MediaAdapterException(code, message + (detail.isBlank() ? "" : "：" + detail));
    }

    private String remoteErrorDetail(byte[] body) {
        if (body == null || body.length == 0) return "";
        try {
            JsonNode payload = json.readTree(body);
            return sanitizeRemoteDetail(firstTextDeep(payload, "message", "status_msg", "msg", "detail", "error_description", "code"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String sanitizeRemoteDetail(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        value = value.replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer ***")
                .replaceAll("(?i)\\b(?:sk|o1)[-_A-Za-z0-9.]{8,}\\b", "***")
                .replaceAll("(?i)(api[_ -]?key|authorization|token)\\s*[:=]\\s*[^\\s,;]+", "$1=***");
        return value.substring(0, Math.min(180, value.length()));
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

    public record ProviderContext(String baseUrl, String apiKey, String imagePath, String videoPath, String voicePath,
                                  String voiceProtocol, String imageProtocol, String videoProtocol) {
        public ProviderContext(String baseUrl, String apiKey, String voicePath) {
            this(baseUrl, apiKey, "", "", voicePath, "openai_audio_speech", "openai_image_generation", "openai_video_generation");
        }
        public ProviderContext(String baseUrl, String apiKey, String imagePath, String videoPath, String voicePath) {
            this(baseUrl, apiKey, imagePath, videoPath, voicePath, "openai_audio_speech", "openai_image_generation", "openai_video_generation");
        }
        public ProviderContext(String baseUrl, String apiKey, String imagePath, String videoPath, String voicePath, String voiceProtocol) {
            this(baseUrl, apiKey, imagePath, videoPath, voicePath, voiceProtocol, "openai_image_generation", "openai_video_generation");
        }
        public ProviderContext(String baseUrl, String apiKey, String imagePath, String videoPath, String voicePath,
                               String voiceProtocol, String imageProtocol) {
            this(baseUrl, apiKey, imagePath, videoPath, voicePath, voiceProtocol, imageProtocol, "openai_video_generation");
        }
        public ProviderContext {
            if (imagePath == null || imagePath.isBlank()) imagePath = "/v1/images/generations";
            if (videoPath == null || videoPath.isBlank()) videoPath = "/v1/videos";
            if (voicePath == null || voicePath.isBlank()) voicePath = "/v1/audio/speech";
            if (voiceProtocol == null || voiceProtocol.isBlank()) voiceProtocol = "openai_audio_speech";
            if (imageProtocol == null || imageProtocol.isBlank()) imageProtocol = "openai_image_generation";
            if (videoProtocol == null || videoProtocol.isBlank()) videoProtocol = "openai_video_generation";
            if (!validPath(imagePath) || !validPath(videoPath) || !validPath(voicePath)) throw new MediaAdapterException("MEDIA_PROTOCOL_UNSUPPORTED", "OpenAI-compatible 媒体 endpoint 无效");
        }
        private static boolean validPath(String value) { return value.startsWith("/") || value.startsWith("https://"); }
    }
    public enum VideoState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED, UNKNOWN }
    public record ImageSubmission(String base64, String url) {}
    public record VideoSubmission(String remoteTaskId, String base64, String url, String contentType) {
        public VideoSubmission(String remoteTaskId) { this(remoteTaskId, "", "", ""); }
    }
    public record VideoPoll(VideoState state, int progress, String error) {}
    public record VideoDownload(long bytesWritten, String contentType) {}
    public record VoiceSubmission(byte[] bytes, String contentType) {}

    public static class MediaAdapterException extends IllegalStateException {
        private final String code;
        public MediaAdapterException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
