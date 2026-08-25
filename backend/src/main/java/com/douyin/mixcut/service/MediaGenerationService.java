package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.domain.MediaGenerationTask;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.repository.Repositories.AiProviderRepo;
import com.douyin.mixcut.repository.Repositories.MediaGenerationTaskRepo;
import com.douyin.mixcut.security.CredentialCipher;
import com.douyin.mixcut.security.UrlGuard;
import com.douyin.mixcut.external.media.MediaAdapterRegistry;
import com.douyin.mixcut.external.media.OpenAiCompatibleMediaAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.nio.file.Files;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Controlled paid media generation. Only fixed, documented provider contracts are allowed here. */
@Service
public class MediaGenerationService {
    private static final String OPENAI_ORIGIN = "https://api.openai.com";
    private final AiProviderRepo providers;
    private final CredentialCipher cipher;
    private final MaterialService materialService;
    private final MediaProviderCatalog mediaCatalog;
    private final AppProps props;
    private final ObjectMapper om;
    private final MediaGenerationTaskRepo generationTaskRepo;
    private final AudioContractService audioContractService;
    private final MediaAdapterRegistry mediaAdapterRegistry;
    @Qualifier("mediaExecutor") private final Executor executor;

    @Autowired
    public MediaGenerationService(AiProviderRepo providers, CredentialCipher cipher, MaterialService materialService,
                                   MediaProviderCatalog mediaCatalog, AppProps props, ObjectMapper om,
                                   MediaGenerationTaskRepo generationTaskRepo, AudioContractService audioContractService,
                                   MediaAdapterRegistry mediaAdapterRegistry, Executor executor) {
        this.providers = providers;
        this.cipher = cipher;
        this.materialService = materialService;
        this.mediaCatalog = mediaCatalog;
        this.props = props;
        this.om = om;
        this.generationTaskRepo = generationTaskRepo;
        this.audioContractService = audioContractService;
        this.mediaAdapterRegistry = mediaAdapterRegistry;
        this.executor = executor;
    }

    public MediaGenerationService(AiProviderRepo providers, CredentialCipher cipher, MaterialService materialService,
                                   MediaProviderCatalog mediaCatalog, AppProps props, ObjectMapper om,
                                   MediaGenerationTaskRepo generationTaskRepo, AudioContractService audioContractService,
                                   OpenAiCompatibleMediaAdapter openAiMediaAdapter, Executor executor) {
        this(providers, cipher, materialService, mediaCatalog, props, om, generationTaskRepo, audioContractService,
                new MediaAdapterRegistry(openAiMediaAdapter), executor);
    }
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final java.util.Set<String> activePollingTasks = ConcurrentHashMap.newKeySet();

    @EventListener(ApplicationReadyEvent.class)
    public void recoverGenerationTasksAtStartup() { recoverGenerationTasks(); }

    @Scheduled(fixedDelayString = "${app.job-watchdog-delay-ms:30000}")
    public void recoverGenerationTasks() {
        try {
            for (MediaGenerationTask persisted : generationTaskRepo.findByStatusOrderByIdAsc("submitting")) {
                if (persisted.getRemoteTaskId() == null || persisted.getRemoteTaskId().isBlank()) {
                    persisted.setStatus("manual_review");
                    persisted.setPhase("submit_unknown");
                    persisted.setMessage("提交结果未知，禁止自动重试以避免重复计费");
                    persisted.setLastActivityAt(java.time.LocalDateTime.now());
                    generationTaskRepo.save(persisted);
                }
            }
            for (MediaGenerationTask persisted : generationTaskRepo.findByPhaseAndRemoteTaskIdIsNotNullOrderByIdAsc("remote_submitted")) {
                restoreVideoPolling(persisted);
            }
            for (MediaGenerationTask persisted : generationTaskRepo.findByPhaseAndRemoteTaskIdIsNotNullOrderByIdAsc("polling")) {
                restoreVideoPolling(persisted);
            }
            recoverInterruptedDownloads();
        } catch (Exception error) {
            // Database may be unavailable during setup mode; the next scheduled pass retries.
        }
    }

    private void recoverInterruptedDownloads() {
        for (MediaGenerationTask persisted : generationTaskRepo.findByPhaseInOrderByIdAsc(List.of("downloading", "validating"))) {
            String stagingPath = persisted.getStagingFilePath();
            Path stagingRoot = props.mediaToolsOutput().resolve("generated-ai-videos").resolve(".staging").toAbsolutePath().normalize();
            if (stagingPath != null && !stagingPath.isBlank()) {
                Path candidate = Path.of(stagingPath).toAbsolutePath().normalize();
                Path expected = stagingRoot.resolve(persisted.getTaskKey() + ".part").normalize();
                if (candidate.startsWith(stagingRoot) && candidate.equals(expected)) {
                    try { Files.deleteIfExists(candidate); } catch (Exception ignored) { }
                }
            }
            persisted.setStagingFilePath(null);
            persisted.setStatus("failed_terminal");
            persisted.setPhase("recovery_failed");
            persisted.setErrorCode("PROCESS_INTERRUPTED");
            persisted.setError("进程在视频下载或素材登记期间退出");
            persisted.setMessage("视频生成未完成，已清理中间文件；请在供应商控制台确认远端任务后人工处理");
            persisted.setLastActivityAt(java.time.LocalDateTime.now());
            generationTaskRepo.save(persisted);
        }
    }

    private void restoreVideoPolling(MediaGenerationTask persisted) {
        if (!activePollingTasks.add(persisted.getTaskKey())) return;
        try {
            AiProvider provider = providers.findById(persisted.getProviderId())
                    .orElseThrow(() -> new IllegalStateException("供应商已不存在，无法恢复远端视频任务"));
            JsonNode snapshot = om.readTree(persisted.getInputSnapshot());
            String prompt = snapshot.path("prompt").asText("");
            String model = persisted.getModel();
            String size = snapshot.path("size").asText("1280x720");
            int seconds = snapshot.path("seconds").asInt(4);
            Task task = fromPersisted(persisted);
            tasks.put(task.getId(), task);
            executor.execute(() -> {
                try { pollVideoWorker(task, provider, model, persisted.getRemoteTaskId()); }
                finally { activePollingTasks.remove(persisted.getTaskKey()); }
            });
        } catch (Exception error) {
            activePollingTasks.remove(persisted.getTaskKey());
            persisted.setStatus("failed_terminal");
            persisted.setPhase("recovery_failed");
            persisted.setError(concise(error));
            persisted.setMessage("远端视频任务恢复失败：" + concise(error));
            persisted.setLastActivityAt(java.time.LocalDateTime.now());
            generationTaskRepo.save(persisted);
        }
    }

    @Data public static class Task {
        private String id;
        private String kind;
        private String status = "accepted";
        private String phase = "accepted";
        private int progress;
        private String message;
        private String errorCode;
        private Integer attemptCount;
        private Integer maxAttempts;
        private Long materialId;
        private String remoteTaskId;
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = createdAt;
    }
    @Data public static class ImageRequest { private Long providerId; private String prompt; private String model = "gpt-image-1"; private String size = "1024x1024"; private String quality = "medium"; private Boolean confirm = false; }
    @Data public static class VideoRequest { private Long providerId; private String prompt; private String model = "sora-2"; private String size = "1280x720"; private Integer seconds = 4; private Boolean confirm = false; }
    @Data public static class VoiceRequest { private Long providerId; private String input; private String model = "gpt-4o-mini-tts"; private String voice = "coral"; private String instructions = ""; private Boolean confirm = false; }

    public List<Map<String, Object>> imageProviders() {
        return providers.findByEnabledTrueOrderByPriorityAsc().stream()
                .filter(p -> p.getApiKey() != null && !p.getApiKey().isBlank())
                .map(p -> {
                    MediaProviderCatalog.Capability capability = mediaCatalog.read(p);
                    Map<String, Object> view = new java.util.LinkedHashMap<>();
                    view.put("id", p.getId());
                    view.put("name", p.getName());
                    view.put("providerMode", isOfficialOpenAi(p) ? "official" : "openai-compatible");
                    view.put("hasKey", true);
                    view.putAll(capability.view());
                    return view;
                })
                .filter(p -> !((List<?>) p.get("imageModels")).isEmpty() || !((List<?>) p.get("videoModels")).isEmpty() || !((List<?>) p.get("voiceModels")).isEmpty())
                .toList();
    }

    private boolean isOfficialOpenAi(AiProvider provider) {
        String base = normalizedBase(provider);
        return OPENAI_ORIGIN.equalsIgnoreCase(base);
    }

    private String normalizedBase(AiProvider provider) {
        String base = provider.getBaseUrl() == null || provider.getBaseUrl().isBlank() ? OPENAI_ORIGIN : provider.getBaseUrl().trim().replaceAll("/+$", "");
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return base;
    }

    private String endpoint(AiProvider provider, String path) {
        String base = normalizedBase(provider);
        URI uri = URI.create(UrlGuard.validate(base));
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("付费 AI Provider 必须使用 HTTPS 地址");
        if (path.startsWith("/v1/") && base.endsWith("/api/v3")) {
            return UrlGuard.validate(base + path.substring(3));
        }
        return UrlGuard.validate(base + path);
    }

    public Task image(ImageRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getConfirm())) throw new IllegalArgumentException("付费 AI 图片生成必须确认供应商、模型和官方计费后才能提交");
        String prompt = request.getPrompt() == null ? "" : request.getPrompt().trim();
        if (prompt.length() < 2 || prompt.length() > 4000) throw new IllegalArgumentException("提示词长度必须在 2 到 4000 个字符之间");
        AiProvider provider = supportedProvider(request.getProviderId());
        // Provider base URL was validated and encrypted through the existing AI settings flow.
        // The browser never supplies it; the service only appends a fixed API path and revalidates the final URL.
        endpoint(provider, "/v1/images/generations");
        String model = requiredModel(provider, "image", request.getModel());
        String size = List.of("1024x1024", "1024x1536", "1536x1024").contains(request.getSize()) ? request.getSize() : "1024x1024";
        String quality = List.of("low", "medium", "high").contains(request.getQuality()) ? request.getQuality() : "medium";
        Task task = newTask("ai-image", "已确认官方计费，正在提交图片生成", provider, model,
                Map.of("providerId", provider.getId(), "prompt", prompt, "model", model, "size", size, "quality", quality));
        executor.execute(() -> generateOpenAiImage(task, provider, prompt, model, size, quality));
        return task;
    }

    public Task video(VideoRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getConfirm())) throw new IllegalArgumentException("付费 AI 视频生成必须确认供应商、模型和官方计费后才能提交");
        String prompt = request.getPrompt() == null ? "" : request.getPrompt().trim();
        if (prompt.length() < 2 || prompt.length() > 4000) throw new IllegalArgumentException("视频提示词长度必须在 2 到 4000 个字符之间");
        AiProvider provider = supportedProvider(request.getProviderId());
        String model = requiredModel(provider, "video", request.getModel());
        String size = List.of("1280x720", "720x1280", "1024x1024").contains(request.getSize()) ? request.getSize() : "1280x720";
        int seconds = request.getSeconds() == null ? 4 : Math.max(2, Math.min(12, request.getSeconds()));
        Task task = newTask("ai-video", "已确认官方计费，正在提交视频生成", provider, model,
                Map.of("providerId", provider.getId(), "prompt", prompt, "model", model, "size", size, "seconds", seconds));
        executor.execute(() -> generateVideo(task, provider, prompt, model, size, seconds));
        return task;
    }

    public Task voice(VoiceRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getConfirm())) throw new IllegalArgumentException("付费 AI 配音必须确认供应商、模型和官方计费后才能提交");
        String input = request.getInput() == null ? "" : request.getInput().trim();
        if (input.length() < 2 || input.length() > 6000) throw new IllegalArgumentException("配音文本长度必须在 2 到 6000 个字符之间");
        AiProvider provider = supportedProvider(request.getProviderId());
        MediaProviderCatalog.Capability capability = mediaCapability(provider);
        mediaAdapterRegistry.adapterFor(provider, "voice", capability);
        String model = requiredModel(provider, "voice", request.getModel());
        String voice = normalizeVoice(provider, model, request.getVoice());
        Task task = newTask("ai-voice", "已确认官方计费，正在生成配音", provider, model,
                Map.of("providerId", provider.getId(), "input", input, "model", model, "voice", voice, "instructions", request.getInstructions() == null ? "" : request.getInstructions().substring(0, Math.min(1000, request.getInstructions().length()))));
        executor.execute(() -> generateVoice(task, provider, input, model, voice, request.getInstructions()));
        return task;
    }

    private AiProvider supportedProvider(Long id) {
        if (id == null) throw new IllegalArgumentException("请选择已配置的 OpenAI-compatible 媒体供应商");
        AiProvider provider = providers.findById(id).orElseThrow(() -> new IllegalArgumentException("供应商不存在"));
        if (!Boolean.TRUE.equals(provider.getEnabled()) || provider.getApiKey() == null || provider.getApiKey().isBlank()) throw new IllegalArgumentException("该供应商未启用、缺少 API Key，或不支持已注册媒体协议");
        endpoint(provider, "/v1/models");
        return provider;
    }

    private String requiredModel(AiProvider provider, String operation, String requested) {
        List<String> available = mediaCatalog.read(provider).models(operation);
        if (available.isEmpty()) throw new IllegalArgumentException("该供应商尚未在 AI 接入页配置" + operation + "模型");
        if (requested == null || requested.isBlank()) return available.get(0);
        if (!available.contains(requested)) throw new IllegalArgumentException("所选模型不在该供应商已配置的" + operation + "能力中");
        return requested;
    }

    private String normalizeVoice(AiProvider provider, String model, String requested) {
        String value = requested == null ? "" : requested.trim();
        if (!value.matches("[A-Za-z0-9._:/-]{1,80}")) value = "";
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("qwen3-tts") || lower.contains("qwen-tts")) return value.isBlank() ? "Cherry" : value;
        return value.isBlank() ? "coral" : value;
    }

    private MediaProviderCatalog.Capability mediaCapability(AiProvider provider) {
        return mediaCatalog.read(provider);
    }

    private Task newTask(String kind, String message) { return newTask(kind, message, null, null, Map.of()); }

    private Task newTask(String kind, String message, AiProvider provider, String model, Map<String, Object> snapshot) {
        Task task = new Task();
        task.setId(UUID.randomUUID().toString());
        task.setKind(kind);
        task.setMessage(message);
        task.setAttemptCount(0);
        task.setMaxAttempts(2);
        tasks.put(task.getId(), task);
        MediaGenerationTask persisted = new MediaGenerationTask();
        persisted.setTaskKey(task.getId());
        persisted.setKind(kind);
        persisted.setStatus("accepted");
        persisted.setPhase("accepted");
        persisted.setProviderId(provider == null ? null : provider.getId());
        persisted.setProvider(provider == null ? null : normalizedBase(provider));
        persisted.setModel(model);
        try { persisted.setInputSnapshot(om.writeValueAsString(snapshot)); } catch (Exception e) { throw new IllegalArgumentException("生成参数无法保存", e); }
        generationTaskRepo.save(persisted);
        return task;
    }

    public List<Task> recent() { return generationTaskRepo.findTop50ByOrderByIdDesc().stream().map(this::fromPersisted).toList(); }
    public Task get(String id) {
        Task task = tasks.get(id);
        if (task != null) return task;
        MediaGenerationTask persisted = generationTaskRepo.findByTaskKey(id).orElseThrow(() -> new IllegalArgumentException("AI 生成任务不存在"));
        return fromPersisted(persisted);
    }

    private Task fromPersisted(MediaGenerationTask persisted) {
        Task task = new Task();
        task.setId(persisted.getTaskKey()); task.setKind(persisted.getKind()); task.setStatus(persisted.getStatus()); task.setPhase(persisted.getPhase());
        task.setProgress(persisted.getProgress() == null ? 0 : persisted.getProgress()); task.setMessage(persisted.getMessage()); task.setErrorCode(persisted.getErrorCode()); task.setAttemptCount(persisted.getAttemptCount()); task.setMaxAttempts(persisted.getMaxAttempts()); task.setRemoteTaskId(persisted.getRemoteTaskId()); task.setMaterialId(persisted.getMaterialId());
        if (persisted.getCreatedAt() != null) task.setCreatedAt(persisted.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        if (persisted.getUpdatedAt() != null) task.setUpdatedAt(persisted.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        return task;
    }

    private void generateVideo(Task task, AiProvider provider, String prompt, String model, String size, int seconds) {
        try {
            beginSubmission(task, 10, "正在调用 OpenAI-compatible 视频生成接口");
            OpenAiCompatibleMediaAdapter mediaAdapter = mediaAdapterRegistry.adapterFor(provider, "video", mediaCapability(provider));
            String remoteId = mediaAdapter.submitVideo(new OpenAiCompatibleMediaAdapter.ProviderContext(
                    normalizedBase(provider), secret(provider), "/v1/audio/speech"), prompt, model, size, seconds).remoteTaskId();
            task.setRemoteTaskId(remoteId);
            update(task, "remote_submitted", 25, "视频任务已提交，等待受控轮询 worker");
            pollVideoWorker(task, provider, model, remoteId);
        } catch (Exception e) {
            persistErrorCode(task.getId(), e);
            update(task, "failed_terminal", task.getProgress(), concise(e));
        }
    }

    private void pollVideoWorker(Task task, AiProvider provider, String model, String remoteId) {
        try {
            String secret = secret(provider);
            update(task, "polling", Math.max(25, task.getProgress()), "轮询远端视频任务状态");
            for (int i = 0; i < 120; i++) {
                Thread.sleep(3000);
                OpenAiCompatibleMediaAdapter mediaAdapter = mediaAdapterRegistry.adapterFor(provider, "video", mediaCapability(provider));
                var poll = mediaAdapter.pollVideo(new OpenAiCompatibleMediaAdapter.ProviderContext(
                        normalizedBase(provider), secret, "/v1/audio/speech"), remoteId);
                int progress = poll.progress() > 0 ? poll.progress() : Math.min(90, 25 + i / 2);
                update(task, "polling", Math.max(25, Math.min(90, progress)), "供应商视频状态：" + poll.state().name().toLowerCase(java.util.Locale.ROOT));
                if (poll.state() == OpenAiCompatibleMediaAdapter.VideoState.SUCCEEDED) { downloadVideo(task, provider, remoteId); return; }
                if (java.util.Set.of(OpenAiCompatibleMediaAdapter.VideoState.FAILED, OpenAiCompatibleMediaAdapter.VideoState.EXPIRED,
                        OpenAiCompatibleMediaAdapter.VideoState.CANCELLED).contains(poll.state())) {
                    throw new IllegalStateException("供应商视频任务未完成：" + poll.state());
                }
            }
            throw new IllegalStateException("视频生成等待超时，请在供应商控制台查看任务状态");
        } catch (Exception e) {
            persistErrorCode(task.getId(), e);
            update(task, "failed_terminal", task.getProgress(), concise(e));
        }
    }

    private void downloadVideo(Task task, AiProvider provider, String remoteId) throws Exception {
        update(task, "downloading", 92, "正在下载并校验生成视频");
        Path dir = props.mediaToolsOutput().resolve("generated-ai-videos");
        Path stagingDir = dir.resolve(".staging");
        Files.createDirectories(stagingDir);
        Path staging = stagingDir.resolve(task.getId() + ".part");
        generationTaskRepo.findByTaskKey(task.getId()).ifPresent(record -> {
            record.setStagingFilePath(staging.toString());
            generationTaskRepo.save(record);
        });
        Path output = dir.resolve("video-" + task.getId() + ".mp4");
        boolean registered = false;
        try {
            OpenAiCompatibleMediaAdapter mediaAdapter = mediaAdapterRegistry.adapterFor(provider, "video", mediaCapability(provider));
            mediaAdapter.downloadVideo(new OpenAiCompatibleMediaAdapter.ProviderContext(
                    normalizedBase(provider), secret(provider), "/v1/audio/speech"), remoteId, staging, props.getNetworkMaxDownloadBytes());
            Files.createDirectories(dir);
            Files.move(staging, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            update(task, "validating", 95, "正在验证视频并登记素材");
            Material material = materialService.register(output.toString(), null, false, Material.Source.generated, normalizedBase(provider));
            registered = true;
            material.setRole(MaterialRole.body); material.setTags("AI生成,视频," + remoteId); material = materialService.save(material); materialService.attachBrowserUrls(material); task.setMaterialId(material.getId());
            clearStagingFilePath(task.getId());
            update(task, "done", 100, "视频生成完成，已作为新素材入库");
        } catch (Exception error) {
            try { Files.deleteIfExists(staging); } catch (Exception ignored) { }
            if (!registered) try { Files.deleteIfExists(output); } catch (Exception ignored) { }
            clearStagingFilePath(task.getId());
            throw error;
        }
    }

    private void clearStagingFilePath(String taskId) {
        generationTaskRepo.findByTaskKey(taskId).ifPresent(record -> {
            record.setStagingFilePath(null);
            generationTaskRepo.save(record);
        });
    }

    private void generateVoice(Task task, AiProvider provider, String input, String model, String voice, String instructions) {
        Path output = null;
        try {
            beginSubmission(task, 15, "正在调用配音接口");
            MediaProviderCatalog.Capability capability = mediaCapability(provider);
            OpenAiCompatibleMediaAdapter mediaAdapter = mediaAdapterRegistry.adapterFor(provider, "voice", capability);
            String voicePath = capability.voiceEndpoint() == null || capability.voiceEndpoint().isBlank() ? "/v1/audio/speech" : capability.voiceEndpoint();
            if (voicePath.startsWith("https://")) {
                throw new OpenAiCompatibleMediaAdapter.MediaAdapterException("MEDIA_PROTOCOL_UNSUPPORTED",
                        "自定义绝对配音 endpoint 需要专用 adapter，当前 OpenAI-compatible adapter 仅支持固定 /v1 路径");
            }
            var artifact = mediaAdapter.submitVoice(new OpenAiCompatibleMediaAdapter.ProviderContext(
                    normalizedBase(provider), secret(provider), voicePath), input, model, voice, instructions);
            Path dir = props.mediaToolsOutput().resolve("generated-ai-audio");
            Files.createDirectories(dir);
            output = dir.resolve("voice-" + Instant.now().toEpochMilli() + ".mp3");
            Files.write(output, artifact.bytes());
            if (!Files.isRegularFile(output) || Files.size(output) < 1024) throw new IllegalStateException("供应商返回的配音文件无效");
            var contract = audioContractService.inspect(output.toString(), 0, "ai-voice", com.douyin.mixcut.external.ProcessRegistry.CancellationContext.none());
            var validation = audioContractService.validate(contract, 0);
            if (!validation.isEmpty()) {
                Files.deleteIfExists(output);
                throw new IllegalStateException("AI 配音音频准入失败：" + String.join(", ", validation));
            }
            Material material = materialService.register(output.toString(), null, false, Material.Source.generated, normalizedBase(provider)); material.setRole(MaterialRole.voice); material.setTags("AI生成,配音," + model + "," + voice); material = materialService.save(material); materialService.attachBrowserUrls(material); task.setMaterialId(material.getId()); update(task, "done", 100, "配音生成完成，已作为新素材入库");
        } catch (Exception e) {
            if (output != null) {
                try { Files.deleteIfExists(output); } catch (Exception cleanup) { }
            }
            persistErrorCode(task.getId(), e);
            update(task, "failed", task.getProgress(), concise(e));
        }
    }

    private void persistErrorCode(String taskId, Exception error) {
        generationTaskRepo.findByTaskKey(taskId).ifPresent(task -> {
            String errorCode;
            if (error instanceof OpenAiCompatibleMediaAdapter.MediaAdapterException adapterError) {
                errorCode = adapterError.code();
            } else if (error instanceof java.net.SocketTimeoutException) {
                errorCode = "TIMEOUT";
            } else {
                errorCode = "MEDIA_EXECUTION_FAILED";
            }
            task.setErrorCode(errorCode);
            task.setError(concise(error));
            generationTaskRepo.save(task);
            Task active = tasks.get(taskId);
            if (active != null) active.setErrorCode(errorCode);
        });
    }

    private String secret(AiProvider provider) { String value = cipher.decrypt(provider.getApiKey()); if (value == null || value.isBlank()) throw new IllegalStateException("供应商密钥不可用，请在 AI 接入页重新配置"); return value.trim(); }
    private HttpURLConnection openPost(String endpoint, String secret, String contentType, byte[] body, int timeout) throws Exception { HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection(); conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setConnectTimeout(20000); conn.setReadTimeout(timeout); conn.setRequestProperty("Content-Type", contentType); conn.setRequestProperty("Authorization", "Bearer " + secret); conn.setFixedLengthStreamingMode(body.length); try (var out = conn.getOutputStream()) { out.write(body); } return conn; }
    private HttpURLConnection openGet(String endpoint, String secret, int timeout) throws Exception { HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection(); conn.setRequestMethod("GET"); conn.setConnectTimeout(20000); conn.setReadTimeout(timeout); conn.setRequestProperty("Authorization", "Bearer " + secret); return conn; }
    private JsonNode getJson(String endpoint, String secret) throws Exception { HttpURLConnection conn = openGet(endpoint, secret, 30000); int status = conn.getResponseCode(); if (status < 200 || status >= 300) throw providerFailure(status, "视频状态查询"); try (InputStream in = conn.getInputStream()) { return om.readTree(in); } finally { conn.disconnect(); } }
    private IllegalStateException providerFailure(int status, String action) {
        String detail = status == 400 ? "请求字段、模型或 voice 不被该接口接受，请按中转文档配置"
                : status == 401 || status == 403 ? "供应商拒绝认证，请检查 API Key、项目权限和账单状态"
                : status == 404 || status == 405 ? action + "接口或模型不可用；该中转可能不支持 OpenAI-compatible 媒体路径"
                : status == 429 ? "供应商限流或额度不足，请检查额度后重试"
                : status >= 500 ? "供应商服务端暂时错误，请稍后重试"
                : action + "失败（HTTP " + status + "）";
        return new IllegalStateException(detail);
    }
    private String concise(Exception e) { String message = e.getMessage() == null ? "生成任务失败" : e.getMessage(); return message.length() > 300 ? message.substring(0, 300) : message; }

    private void generateOpenAiImage(Task task, AiProvider provider, String prompt, String model, String size, String quality) {
        try {
            beginSubmission(task, 15, "正在调用 OpenAI-compatible 图片生成接口");
            OpenAiCompatibleMediaAdapter mediaAdapter = mediaAdapterRegistry.adapterFor(provider, "image", mediaCapability(provider));
            var submission = mediaAdapter.submitImage(new OpenAiCompatibleMediaAdapter.ProviderContext(
                    normalizedBase(provider), secret(provider), "/v1/audio/speech"), prompt, model, size, quality);

            update(task, "running", 75, "正在校验并登记生成图片"); Path dir = props.mediaToolsOutput().resolve("generated-ai-images"); Files.createDirectories(dir); Path output = dir.resolve("openai-" + Instant.now().toEpochMilli() + ".png");
            if (!submission.base64().isBlank()) Files.write(output, Base64.getDecoder().decode(submission.base64()));
            else downloadGeneratedImage(submission.url(), output);
            if (!Files.isRegularFile(output) || Files.size(output) < 512) throw new IllegalStateException("生成图片无效");
            Material material = materialService.register(output.toString(), null, false, Material.Source.generated, normalizedBase(provider)); material.setRole(MaterialRole.product); material.setTags("AI生成,OpenAI," + model); material = materialService.save(material); materialService.attachBrowserUrls(material);
            task.setMaterialId(material.getId()); update(task, "done", 100, "图片生成完成，已作为新素材入库");
        } catch (Exception e) {
            persistErrorCode(task.getId(), e);
            update(task, "failed", task.getProgress(), e.getMessage() == null ? "图片生成失败" : e.getMessage());
        }
    }

    private void downloadGeneratedImage(String rawUrl, Path output) throws Exception {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalStateException("官方服务未返回图片数据");
        URI uri = URI.create(UrlGuard.validate(rawUrl));
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalStateException("生成图片下载地址必须是 HTTPS");
        HttpURLConnection conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(180000);
        conn.setRequestProperty("Accept", "image/png,image/jpeg,image/webp,*/*");
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("生成图片下载失败（HTTP " + status + "）");
        long max = 20L * 1024 * 1024;
        long written = 0;
        try (InputStream in = conn.getInputStream(); var out = Files.newOutputStream(output)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                written += n;
                if (written > max) throw new IllegalStateException("生成图片超过 20MB 限制");
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    private void beginSubmission(Task task, int progress, String message) {
        MediaGenerationTask persisted = generationTaskRepo.findByTaskKey(task.getId()).orElse(null);
        int attemptCount = persisted == null || persisted.getAttemptCount() == null ? 1 : persisted.getAttemptCount() + 1;
        task.setAttemptCount(attemptCount);
        if (persisted != null) {
            persisted.setAttemptCount(attemptCount);
            generationTaskRepo.save(persisted);
        }
        update(task, "submitting", progress, message);
    }

    private void update(Task task, String status, int progress, String message) {
        task.setStatus(status); task.setPhase(status); task.setProgress(Math.max(0, Math.min(100, progress))); task.setMessage(message); task.setUpdatedAt(System.currentTimeMillis());
        MediaGenerationTask persisted = generationTaskRepo.findByTaskKey(task.getId()).orElse(null);
        if (persisted != null) {
            persisted.setStatus(status); persisted.setPhase(status); persisted.setProgress(task.getProgress()); persisted.setMessage(message); persisted.setRemoteTaskId(task.getRemoteTaskId()); persisted.setMaterialId(task.getMaterialId()); persisted.setLastActivityAt(java.time.LocalDateTime.now());
            generationTaskRepo.save(persisted);
        }
    }
}
