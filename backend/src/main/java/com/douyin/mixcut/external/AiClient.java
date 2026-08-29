package com.douyin.mixcut.external;

import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.domain.ProviderKind;
import com.douyin.mixcut.security.CredentialCipher;
import com.douyin.mixcut.security.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 统一 AI 调用客户端。
 *
 * 同时支持三类官方/兼容接口，前端只需填 base_url + key + 模型名：
 * - openai    ：/v1/chat/completions（OpenAI、DeepSeek、Moonshot、通义、智谱、各类中转站都吃这套）
 * - anthropic ：/v1/messages
 * - gemini    ：/v1beta/models/{model}:generateContent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClient {

    private final ObjectMapper om = new ObjectMapper();
    private final CredentialCipher credentialCipher;

    @Data
    public static class ChatResult {
        private boolean ok;
        private String text;
        private String error;
        private String errorCode;
        private int promptTokens;
        private int completionTokens;
        private long latencyMs;

        public static ChatResult fail(String err) {
            return fail("AI_REQUEST_FAILED", err);
        }

        public static ChatResult fail(String code, String err) {
            ChatResult r = new ChatResult();
            r.ok = false;
            r.errorCode = code;
            r.error = err;
            return r;
        }
    }

    public ChatResult chat(AiProvider p, String model, String system, String user, double temperature, int maxTokens) {
        long t0 = System.currentTimeMillis();
        try {
            ChatResult r = switch (p.getKind() == null ? ProviderKind.openai : p.getKind()) {
                case openai -> callOpenAi(p, model, system, user, temperature, maxTokens);
                case anthropic -> callAnthropic(p, model, system, user, temperature, maxTokens);
                case gemini -> callGemini(p, model, system, user, temperature, maxTokens);
            };
            r.setLatencyMs(System.currentTimeMillis() - t0);
            return r;
        } catch (IllegalStateException e) {
            ChatResult r = ChatResult.fail("AI_CONFIG_INVALID", "AI 配置不可用: " + safeUrlError(e));
            r.setLatencyMs(System.currentTimeMillis() - t0);
            return r;
        } catch (IllegalArgumentException e) {
            ChatResult r = ChatResult.fail("AI_URL_INVALID", "AI 服务 URL 格式错误或目标地址不允许访问: " + safeUrlError(e));
            r.setLatencyMs(System.currentTimeMillis() - t0);
            return r;
        } catch (Exception e) {
            ChatResult r = ChatResult.fail("AI_NETWORK_ERROR", "AI 服务请求失败: " + safeUrlError(e));
            r.setLatencyMs(System.currentTimeMillis() - t0);
            return r;
        }
    }

    /** 连通性测试：一个字都不多要，只验证 key/base_url/模型能不能通 */
    public ChatResult ping(AiProvider p, String model) {
        return chat(p, model, "You are a health check endpoint.", "Reply with exactly: OK", 0, 16);
    }

    /**
     * Discover models exposed by the configured endpoint. Media categories are only populated
     * when the provider declares matching capabilities or the model id has an unambiguous
     * generation suffix. Ordinary chat and vision-understanding models stay text/vision only.
     */
    public ModelDiscovery discoverModels(AiProvider provider) {
        long started = System.currentTimeMillis();
        try {
            ProviderKind kind = provider.getKind() == null ? ProviderKind.openai : provider.getKind();
            JsonNode root;
            if (kind == ProviderKind.openai) {
                root = get(buildValidatedUrl(base(provider, "https://api.openai.com"), "/v1/models"), bearerHeaders(provider));
            } else if (kind == ProviderKind.gemini) {
                root = get(buildValidatedUrl(base(provider, "https://generativelanguage.googleapis.com"), "/v1beta/models"), googleHeaders(provider));
            } else {
                return ModelDiscovery.fail("Anthropic 原生接口没有统一的模型列表接口，请按官方控制台或文档填写模型名");
            }
            List<String> text = new ArrayList<>();
            List<String> image = new ArrayList<>();
            List<String> video = new ArrayList<>();
            List<String> voice = new ArrayList<>();
            List<String> vision = new ArrayList<>();
            JsonNode items = kind == ProviderKind.gemini ? root.path("models") : root.path("data");
            for (JsonNode item : items) {
                String id = item.path(kind == ProviderKind.gemini ? "name" : "id").asText("")
                        .replaceFirst("^models/", "").trim();
                if (id.isBlank()) continue;
                Set<String> declared = declaredCapabilities(item);
                String lower = id.toLowerCase(java.util.Locale.ROOT);
                boolean isImage = isImageGenerationModel(lower, declared)
                        || hasAny(declared, "output_image", "image_output");
                boolean isVideo = hasAny(declared, "video_generation", "video-generation", "text-to-video", "text_to_video")
                        || lower.matches(".*(sora|veo|seedance|kling|runway|cogvideo|wan[0-9.]*[-_]?video|video-gen|video-generation|text-to-video).*" );
                boolean isVoice = hasAny(declared, "speech", "tts", "audio_generation", "voice_generation", "output_audio", "output_voice", "text-to-speech", "text_to_speech")
                        || lower.matches(".*(tts|speech|voice|audio-gen|audiogen|cosyvoice|fish-speech).*" );
                boolean isVision = hasAny(declared, "vision", "image_input", "image-input", "input_image", "image_understanding", "multimodal")
                        || lower.matches(".*(vision|vl|multimodal|qwen2-vl|qwen-vl|gemini).*" )
                        || looksVisionCapableGpt(lower);
                if (!isImage && !isVideo && !isVoice) addUnique(text, id);
                if (isImage) addUnique(image, id);
                if (isVideo) addUnique(video, id);
                if (isVoice) addUnique(voice, id);
                if (isVision) addUnique(vision, id);
            }
            if (text.isEmpty() && image.isEmpty() && video.isEmpty() && voice.isEmpty() && vision.isEmpty()) {
                return ModelDiscovery.fail("Provider 返回了空模型列表");
            }
            long now = System.currentTimeMillis();
            return new ModelDiscovery(true, limit(text), limit(image), limit(video), limit(voice), limit(vision), null,
                    System.currentTimeMillis() - started, now);
        } catch (IllegalArgumentException e) {
            return ModelDiscovery.fail("模型探测地址不允许访问：" + safeUrlError(e));
        } catch (Exception e) {
            return ModelDiscovery.fail("模型探测失败：" + safeUrlError(e));
        }
    }

    public record ModelDiscovery(boolean ok, List<String> textModels, List<String> imageModels,
                                 List<String> videoModels, List<String> voiceModels, List<String> visionModels,
                                 String error, long latencyMs, long discoveredAt) {
        public List<String> models() { return textModels; }
        static ModelDiscovery fail(String error) {
            return new ModelDiscovery(false, List.of(), List.of(), List.of(), List.of(), List.of(), error, 0, 0);
        }
    }

    private Set<String> declaredCapabilities(JsonNode item) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        collectDeclaredCapabilities(item, result, 0);
        return result;
    }

    /**
     * Gateways put capability metadata at different nesting levels, for example
     * capabilities.input/output or model.modalities. Read only capability-shaped
     * fields and never infer media support from the model name alone unless the
     * name is an unambiguous generation model.
     */
    private void collectDeclaredCapabilities(JsonNode node, Set<String> result, int depth) {
        if (node == null || depth > 4) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String field = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (field.contains("capabilit") || field.contains("modalit") || field.contains("supported")
                        || field.contains("generation") || field.contains("input") || field.contains("output")
                        || field.contains("feature") || field.contains("method")) {
                    String prefix = field.contains("output") ? "output_" : field.contains("input") ? "input_" : "";
                    collectCapabilityValues(entry.getValue(), result, depth + 1, prefix);
                } else if (entry.getValue().isObject() && depth < 3) {
                    collectDeclaredCapabilities(entry.getValue(), result, depth + 1);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode value : node) collectDeclaredCapabilities(value, result, depth + 1);
        }
    }

    private void collectCapabilityValues(JsonNode node, Set<String> result, int depth, String prefix) {
        if (node == null || depth > 4) return;
        if (node.isTextual()) {
            String value = node.asText("").trim().toLowerCase(java.util.Locale.ROOT);
            if (!value.isBlank()) {
                result.add(value);
                if (!prefix.isBlank()) result.add(prefix + value.replace('-', '_'));
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode value : node) collectCapabilityValues(value, result, depth + 1, prefix);
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String field = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                String nestedPrefix = field.contains("output") ? "output_" : field.contains("input") ? "input_" : prefix;
                collectCapabilityValues(entry.getValue(), result, depth + 1, nestedPrefix);
            });
        }
    }

    private boolean hasAny(Set<String> values, String... expected) {
        for (String value : values) for (String token : expected) if (value.contains(token)) return true;
        return false;
    }

    private void addUnique(List<String> values, String value) {
        if (!values.contains(value)) values.add(value);
    }

    static boolean isImageGenerationModel(String lower, Set<String> declared) {
        return hasAnyStatic(declared, "image_generation", "image-generation", "text-to-image", "text_to_image")
                || lower.matches(".*(gpt-image|dall[-.]e|imagen|stable-diffusion|stable_diffusion|flux|seedream|jimeng|qwen-image|wan[0-9.]*[-_]?image|image-gen|image-generation).*" );
    }

    private static boolean hasAnyStatic(Set<String> values, String... expected) {
        for (String value : values) for (String token : expected) if (value.contains(token)) return true;
        return false;
    }

    public static boolean looksVisionCapableGpt(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("^gpt-(4.*|4o.*|4\\.1.*|5.*)$");
    }

    private List<String> limit(List<String> values) {
        return List.copyOf(values.subList(0, Math.min(100, values.size())));
    }

    // ---------------- OpenAI 兼容 ----------------

    private ChatResult callOpenAi(AiProvider p, String model, String system, String user,
                                  double temperature, int maxTokens) {
        String url = buildValidatedUrl(base(p, "https://api.openai.com"), "/v1/chat/completions");

        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        ArrayNode msgs = body.putArray("messages");
        if (system != null && !system.isBlank()) {
            ObjectNode m = msgs.addObject();
            m.put("role", "system");
            m.put("content", system);
        }
        ObjectNode m2 = msgs.addObject();
        m2.put("role", "user");
        m2.put("content", user);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + secret(p));

        JsonNode resp = post(url, body, h);
        if (resp == null) return ChatResult.fail("AI_EMPTY_RESPONSE", "空响应");
        if (resp.has("errorCode")) return failedResponse(resp);

        ChatResult r = new ChatResult();
        r.setOk(true);
        r.setText(resp.path("choices").path(0).path("message").path("content").asText(""));
        r.setPromptTokens(resp.path("usage").path("prompt_tokens").asInt(0));
        r.setCompletionTokens(resp.path("usage").path("completion_tokens").asInt(0));
        if (r.getText().isBlank()) return ChatResult.fail("AI_RESPONSE_INVALID", "返回内容为空: " + redact(shorten(resp.toString())));
        return r;
    }

    // ---------------- Anthropic ----------------

    private ChatResult callAnthropic(AiProvider p, String model, String system, String user,
                                     double temperature, int maxTokens) {
        String url = buildValidatedUrl(base(p, "https://api.anthropic.com"), "/v1/messages");

        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        if (system != null && !system.isBlank()) body.put("system", system);
        ArrayNode msgs = body.putArray("messages");
        ObjectNode m = msgs.addObject();
        m.put("role", "user");
        ArrayNode content = m.putArray("content");
        ObjectNode c = content.addObject();
        c.put("type", "text");
        c.put("text", user);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("x-api-key", secret(p));
        h.set("anthropic-version", "2023-06-01");

        JsonNode resp = post(url, body, h);
        if (resp == null) return ChatResult.fail("AI_EMPTY_RESPONSE", "空响应");
        if (resp.has("errorCode")) return failedResponse(resp);

        StringBuilder sb = new StringBuilder();
        for (JsonNode block : resp.path("content")) {
            if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
        }
        ChatResult r = new ChatResult();
        r.setOk(true);
        r.setText(sb.toString());
        r.setPromptTokens(resp.path("usage").path("input_tokens").asInt(0));
        r.setCompletionTokens(resp.path("usage").path("output_tokens").asInt(0));
        if (r.getText().isBlank()) return ChatResult.fail("AI_RESPONSE_INVALID", "返回内容为空: " + redact(shorten(resp.toString())));
        return r;
    }

    // ---------------- Gemini ----------------

    private ChatResult callGemini(AiProvider p, String model, String system, String user,
                                  double temperature, int maxTokens) {
        String b = base(p, "https://generativelanguage.googleapis.com");
        String url = buildValidatedUrl(b, "/v1beta/models/" + model + ":generateContent");

        ObjectNode body = om.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode c = contents.addObject();
        c.put("role", "user");
        ObjectNode part = c.putArray("parts").addObject();
        part.put("text", (system == null || system.isBlank()) ? user : system + "\n\n" + user);

        ObjectNode gen = body.putObject("generationConfig");
        gen.put("temperature", temperature);
        gen.put("maxOutputTokens", maxTokens);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("x-goog-api-key", secret(p));

        JsonNode resp = post(url, body, h);
        if (resp == null) return ChatResult.fail("AI_EMPTY_RESPONSE", "空响应");
        if (resp.has("errorCode")) return failedResponse(resp);

        StringBuilder sb = new StringBuilder();
        for (JsonNode pt : resp.path("candidates").path(0).path("content").path("parts")) {
            sb.append(pt.path("text").asText(""));
        }
        ChatResult r = new ChatResult();
        r.setOk(true);
        r.setText(sb.toString());
        r.setPromptTokens(resp.path("usageMetadata").path("promptTokenCount").asInt(0));
        r.setCompletionTokens(resp.path("usageMetadata").path("candidatesTokenCount").asInt(0));
        if (r.getText().isBlank()) return ChatResult.fail("AI_RESPONSE_INVALID", "返回内容为空: " + redact(shorten(resp.toString())));
        return r;
    }

    // ---------------- 公共 ----------------

    private HttpHeaders bearerHeaders(AiProvider provider) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + secret(provider));
        return headers;
    }

    private HttpHeaders googleHeaders(AiProvider provider) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-goog-api-key", secret(provider));
        return headers;
    }

    private JsonNode get(String url, HttpHeaders headers) throws Exception {
        String validated = UrlGuard.validate(url);
        HttpURLConnection conn = (HttpURLConnection) new URL(validated).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(false);
            for (var entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), String.join(",", entry.getValue()));
            }
            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) throw new IllegalArgumentException("模型探测不接受跨域重定向");
            String response = readResponse(conn, status);
            if (status >= 400) throw new IllegalStateException(discoveryHttpError(status));
            if (response == null || response.isBlank()) throw new IllegalStateException("Provider 返回空模型列表");
            return om.readTree(response);
        } finally {
            conn.disconnect();
        }
    }

    private JsonNode post(String url, ObjectNode body, HttpHeaders h) {
        // Defend against accidental future callers bypassing the endpoint builder.
        final String initialUrl = UrlGuard.validate(url);
        final byte[] payload;
        try {
            payload = om.writeValueAsBytes(body);
        } catch (Exception e) {
            return errorNode("AI_REQUEST_INVALID", "无法序列化 AI 请求");
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection conn = null;
            String current = initialUrl;
            boolean retryRequested = false;
            try {
                for (int redirects = 0; redirects <= 3; redirects++) {
                    conn = (HttpURLConnection) new URL(current).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(120000);
                    conn.setInstanceFollowRedirects(false);
                    for (var entry : h.entrySet()) {
                        conn.setRequestProperty(entry.getKey(), String.join(",", entry.getValue()));
                    }
                    conn.setFixedLengthStreamingMode(payload.length);
                    try (var out = conn.getOutputStream()) {
                        out.write(payload);
                    }
                    int status = conn.getResponseCode();
                    if (isRedirect(status)) {
                        String next = redirectTarget(current, conn.getHeaderField("Location"));
                        conn.disconnect();
                        conn = null;
                        if (next == null) return errorNode("AI_REDIRECT_INVALID", "AI 服务重定向地址不允许访问");
                        current = next;
                        continue;
                    }
                    String response = readResponse(conn, status);
                    if ((status == 429 || status >= 500) && attempt < 2) {
                        conn.disconnect();
                        conn = null;
                        retryRequested = true;
                        break;
                    }
                    if (status >= 400) {
                        // Do not surface provider error bodies: proxies may echo credentials in diagnostics.
                        return errorNode(classifyHttpStatus(status), "AI 服务返回 HTTP " + status);
                    }
                    if (response == null || response.isBlank()) return null;
                    try {
                        return om.readTree(response);
                    } catch (Exception ignored) {
                        return errorNode("AI_RESPONSE_INVALID", "AI 服务返回了无效响应");
                    }
                }
                if (retryRequested) {
                    backoff(attempt);
                    continue;
                }
                return errorNode("AI_REDIRECT_INVALID", "AI 服务重定向次数过多");
            } catch (Exception e) {
                if (attempt < 2) {
                    backoff(attempt);
                    continue;
                }
                return errorNode("AI_NETWORK_ERROR", "AI 服务网络访问失败");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return errorNode("AI_NETWORK_ERROR", "AI 服务请求失败");
    }

    private String readResponse(HttpURLConnection conn, int status) throws Exception {
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return null;
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String redirectTarget(String currentUrl, String location) {
        if (location == null || location.isBlank()) return null;
        try {
            URI current = URI.create(currentUrl);
            URI target = URI.create(currentUrl).resolve(location.trim());
            // The request carries an API-key header. Never forward it to a different origin.
            if (!sameOrigin(current, target)) return null;
            return UrlGuard.validate(target.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307 || status == 308;
    }

    private ChatResult failedResponse(JsonNode response) {
        String code = response.path("errorCode").asText("AI_REMOTE_ERROR");
        String message = response.path("error").asText("AI 服务返回错误");
        return ChatResult.fail(code, message);
    }

    private ObjectNode errorNode(String code, String message) {
        ObjectNode n = om.createObjectNode();
        n.put("errorCode", code);
        n.put("error", shorten(message));
        return n;
    }

    static String classifyHttpStatus(int status) {
        if (status == 401 || status == 403) return "AI_AUTH_REQUIRED";
        if (status == 408 || status == 504) return "AI_TIMEOUT";
        if (status == 404 || status == 405) return "AI_ENDPOINT_UNSUPPORTED";
        if (status == 429) return "AI_RATE_LIMITED";
        if (status >= 500) return "AI_REMOTE_SERVER_ERROR";
        if (status >= 400) return "AI_REQUEST_REJECTED";
        return "AI_HTTP_ERROR";
    }

    private String discoveryHttpError(int status) {
        return classifyHttpStatus(status) + ": Provider 返回 HTTP " + status;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(200L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String base(AiProvider p, String dft) {
        String b = p.getBaseUrl();
        if (b == null || b.isBlank()) return dft;
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /**
     * Builds an AI endpoint and validates the final URL. Package-private for focused unit tests.
     * Provider base URLs may already contain /v1 or /v1beta, so duplicate version segments are removed.
     */
    static String buildValidatedUrl(String base, String path) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("AI 服务 baseUrl 不能为空");
        }
        String normalizedBase = base.trim();
        while (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }
        if (normalizedBase.isBlank()) {
            throw new IllegalArgumentException("AI 服务 baseUrl 格式错误");
        }
        final URI baseUri;
        try {
            baseUri = URI.create(normalizedBase);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AI 服务 baseUrl 格式错误", e);
        }
        if (baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("AI 服务 baseUrl 不能包含查询参数或片段");
        }
        String joined;
        if (path.startsWith("/v1/") && normalizedBase.endsWith("/v1")) {
            joined = normalizedBase + path.substring(3);
        } else if (path.startsWith("/v1/") && normalizedBase.endsWith("/api/v3")) {
            joined = normalizedBase + path.substring(3);
        } else if (path.startsWith("/v1beta/") && normalizedBase.endsWith("/v1beta")) {
            joined = normalizedBase + path.substring(7);
        } else {
            joined = normalizedBase + path;
        }
        return UrlGuard.validate(joined);
    }

    /** Validates a supplied provider base URL before it is persisted or used for a test request. */
    public static void validateProviderBaseUrl(String baseUrl, ProviderKind kind) {
        String defaultBase = switch (kind == null ? ProviderKind.openai : kind) {
            case openai -> "https://api.openai.com";
            case anthropic -> "https://api.anthropic.com";
            case gemini -> "https://generativelanguage.googleapis.com";
        };
        buildValidatedUrl(baseUrl == null || baseUrl.isBlank() ? defaultBase : baseUrl, "/");
    }

    private String secret(AiProvider provider) {
        String value = credentialCipher.decrypt(provider.getApiKey());
        if (value == null || value.isBlank()) throw new IllegalArgumentException("该供应商未配置密钥");
        return value.trim();
    }

    private String safeUrlError(Exception e) {
        return redact(shorten(e.getMessage()));
    }

    private String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 600 ? s : s.substring(0, 600) + "...";
    }

    private String redact(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)(key|token|api[_-]?key|authorization)=?[^\\s&]+", "$1=***")
                .replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer ***");
    }

    public static List<String> defaultModels(ProviderKind kind) {
        return switch (kind) {
            case openai -> List.of("gpt-4o-mini", "gpt-4o", "deepseek-chat", "doubao-seed-1-6-250615", "qwen-plus", "qwen-vl-plus", "moonshot-v1-8k", "glm-4-flash");
            case anthropic -> List.of("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022");
            case gemini -> List.of("gemini-2.0-flash", "gemini-1.5-pro");
        };
    }
}
