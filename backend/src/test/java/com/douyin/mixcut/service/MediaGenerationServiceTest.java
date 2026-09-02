package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.AiProvider;
import com.douyin.mixcut.domain.MediaGenerationTask;
import com.douyin.mixcut.external.media.OpenAiCompatibleMediaAdapter;
import com.douyin.mixcut.repository.Repositories.AiProviderRepo;
import com.douyin.mixcut.repository.Repositories.MediaGenerationTaskRepo;
import com.douyin.mixcut.security.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaGenerationServiceTest {
    @Test
    void remoteSubmittedVideoStateIsRecoverableWithoutResubmitting() {
        MediaGenerationTask task = new MediaGenerationTask();
        task.setTaskKey("video-1");
        task.setKind("ai-video");
        task.setStatus("polling");
        task.setPhase("polling");
        task.setRemoteTaskId("remote-123");
        task.setLastActivityAt(LocalDateTime.now());
        assertEquals("polling", task.getStatus());
        assertEquals("remote-123", task.getRemoteTaskId());
        assertTrue(task.getRemoteTaskId() != null && !task.getRemoteTaskId().isBlank());
    }

    @Test
    void unknownSynchronousSubmissionStateIsManualReview() {
        MediaGenerationTask task = new MediaGenerationTask();
        task.setTaskKey("image-unknown");
        task.setKind("ai-image");
        task.setStatus("manual_review");
        task.setPhase("submit_unknown");
        task.setMessage("提交结果未知，禁止自动重试以避免重复计费");
        assertEquals("manual_review", task.getStatus());
        assertEquals("submit_unknown", task.getPhase());
        assertTrue(task.getMessage().contains("禁止自动重试"));
    }

    @Test
    void interruptedDownloadRecoveryRemovesOwnedStagingAndMarksTerminal(@TempDir Path tempDir) throws Exception {
        MediaGenerationTaskRepo taskRepo = mock(MediaGenerationTaskRepo.class);
        MediaGenerationTask persisted = new MediaGenerationTask();
        persisted.setTaskKey("video-1"); persisted.setPhase("downloading"); persisted.setStatus("downloading");
        Path staging = tempDir.resolve("generated-ai-videos/.staging/video-1.part");
        Files.createDirectories(staging.getParent()); Files.write(staging, new byte[10]);
        persisted.setStagingFilePath(staging.toString());
        when(taskRepo.findByStatusOrderByIdAsc(any())).thenReturn(java.util.List.of());
        when(taskRepo.findByPhaseAndRemoteTaskIdIsNotNullOrderByIdAsc(any())).thenReturn(java.util.List.of());
        when(taskRepo.findByPhaseInOrderByIdAsc(any())).thenReturn(java.util.List.of(persisted));
        AppProps props = new AppProps(); props.setMediaToolsOutputDir(tempDir.toString());
        MediaGenerationService service = new MediaGenerationService(mock(AiProviderRepo.class), mock(CredentialCipher.class), mock(MaterialService.class), mock(MediaProviderCatalog.class), props, new ObjectMapper(), taskRepo, mock(AudioContractService.class), mock(OpenAiCompatibleMediaAdapter.class), (Executor) Runnable::run);
        Method recovery = MediaGenerationService.class.getDeclaredMethod("recoverInterruptedDownloads"); recovery.setAccessible(true);
        recovery.invoke(service);
        assertFalse(Files.exists(staging));
        assertEquals("recovery_failed", persisted.getPhase());
        assertEquals("PROCESS_INTERRUPTED", persisted.getErrorCode());
        assertEquals(null, persisted.getStagingFilePath());
    }

    @Test
    void failedVideoDownloadDeletesStagingAndClearsPersistedPath(@TempDir Path tempDir) throws Exception {
        AiProviderRepo providers = mock(AiProviderRepo.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        MediaGenerationTaskRepo taskRepo = mock(MediaGenerationTaskRepo.class);
        OpenAiCompatibleMediaAdapter adapter = mock(OpenAiCompatibleMediaAdapter.class);
        MediaGenerationTask persisted = new MediaGenerationTask();
        persisted.setTaskKey("video-1");
        when(taskRepo.findByTaskKey("video-1")).thenReturn(Optional.of(persisted));
        when(cipher.decrypt(any())).thenReturn("secret");
        when(adapter.downloadVideo(any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    Path staging = invocation.getArgument(2);
                    Files.write(staging, new byte[1024]);
                    throw new OpenAiCompatibleMediaAdapter.MediaAdapterException("DOWNLOAD_FILE_TOO_SMALL", "视频文件无效");
                });
        AppProps props = new AppProps();
        props.setMediaToolsOutputDir(tempDir.toString());
        MediaGenerationService service = new MediaGenerationService(
                providers, cipher, mock(MaterialService.class), mock(MediaProviderCatalog.class), props, new ObjectMapper(),
                taskRepo, mock(AudioContractService.class), adapter, (Executor) Runnable::run);
        MediaGenerationService.Task task = new MediaGenerationService.Task();
        task.setId("video-1");
        AiProvider provider = new AiProvider();
        provider.setBaseUrl("https://api.openai.com");
        provider.setApiKey("encrypted-key");
        Method download = MediaGenerationService.class.getDeclaredMethod("downloadVideo", MediaGenerationService.Task.class, AiProvider.class, String.class);
        download.setAccessible(true);

        assertThrows(InvocationTargetException.class, () -> download.invoke(service, task, provider, "remote-1"));

        Path staging = tempDir.resolve("generated-ai-videos/.staging/video-1.part");
        assertFalse(Files.exists(staging));
        assertEquals(null, persisted.getStagingFilePath());
    }

    @Test
    void failedMaterialRegistrationDeletesMovedOutput(@TempDir Path tempDir) throws Exception {
        AiProviderRepo providers = mock(AiProviderRepo.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        MediaGenerationTaskRepo taskRepo = mock(MediaGenerationTaskRepo.class);
        OpenAiCompatibleMediaAdapter adapter = mock(OpenAiCompatibleMediaAdapter.class);
        MaterialService materials = mock(MaterialService.class);
        MediaGenerationTask persisted = new MediaGenerationTask();
        persisted.setTaskKey("video-1");
        when(taskRepo.findByTaskKey("video-1")).thenReturn(Optional.of(persisted));
        when(cipher.decrypt(any())).thenReturn("secret");
        when(adapter.downloadVideo(any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            Path staging = invocation.getArgument(2);
            Files.write(staging, new byte[4096]);
            return new OpenAiCompatibleMediaAdapter.VideoDownload(4096, "video/mp4");
        });
        when(materials.register(any(), isNull(), anyBoolean(), any(), any())).thenThrow(new IllegalStateException("probe failed"));
        AppProps props = new AppProps();
        props.setMediaToolsOutputDir(tempDir.toString());
        MediaGenerationService service = new MediaGenerationService(
                providers, cipher, materials, mock(MediaProviderCatalog.class), props, new ObjectMapper(),
                taskRepo, mock(AudioContractService.class), adapter, (Executor) Runnable::run);
        MediaGenerationService.Task task = new MediaGenerationService.Task();
        task.setId("video-1");
        AiProvider provider = new AiProvider();
        provider.setBaseUrl("https://api.openai.com");
        provider.setApiKey("encrypted-key");
        Method download = MediaGenerationService.class.getDeclaredMethod("downloadVideo", MediaGenerationService.Task.class, AiProvider.class, String.class);
        download.setAccessible(true);

        assertThrows(InvocationTargetException.class, () -> download.invoke(service, task, provider, "remote-1"));

        assertFalse(Files.exists(tempDir.resolve("generated-ai-videos/video-video-1.mp4")));
        assertEquals(null, persisted.getStagingFilePath());
    }

    @Test
    void persistedTaskViewIncludesDiagnostics() {
        MediaGenerationTask persisted = new MediaGenerationTask();
        persisted.setTaskKey("video-1");
        persisted.setKind("ai-video");
        persisted.setStatus("failed_terminal");
        persisted.setPhase("polling");
        persisted.setErrorCode("RATE_LIMITED");
        persisted.setProviderId(4L);
        persisted.setModel("gpt-image-2");
        persisted.setAttemptCount(1);
        persisted.setMaxAttempts(2);
        MediaGenerationTaskRepo taskRepo = mock(MediaGenerationTaskRepo.class);
        when(taskRepo.findTop50ByOrderByIdDesc()).thenReturn(java.util.List.of(persisted));
        MediaGenerationService service = new MediaGenerationService(
                mock(AiProviderRepo.class), mock(CredentialCipher.class), mock(MaterialService.class), mock(MediaProviderCatalog.class),
                new AppProps(), new ObjectMapper(), taskRepo, mock(AudioContractService.class), mock(OpenAiCompatibleMediaAdapter.class), (Executor) Runnable::run);

        MediaGenerationService.Task view = service.recent().get(0);

        assertEquals("polling", view.getPhase());
        assertEquals("RATE_LIMITED", view.getErrorCode());
        assertEquals(1, view.getAttemptCount());
        assertEquals(2, view.getMaxAttempts());
        assertEquals("历史供应商 #4", view.getProviderName());
        assertFalse(view.isProviderAvailable());
        assertEquals("gpt-image-2", view.getModel());
    }

    @Test
    void legacyDashScopeWorkspaceKeyIsExcludedFromExecutableProviders() {
        AiProviderRepo providerRepo = mock(AiProviderRepo.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        AiProvider provider = new AiProvider();
        provider.setId(3L);
        provider.setName("错误地址密钥组合");
        provider.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setApiKey("encrypted-key");
        provider.setEnabled(true);
        when(providerRepo.findByEnabledTrueOrderByPriorityAsc()).thenReturn(java.util.List.of(provider));
        when(cipher.decrypt("encrypted-key")).thenReturn("sk-ws-test");
        MediaGenerationService service = new MediaGenerationService(
                providerRepo, cipher, mock(MaterialService.class), mock(MediaProviderCatalog.class),
                new AppProps(), new ObjectMapper(), mock(MediaGenerationTaskRepo.class),
                mock(AudioContractService.class), mock(OpenAiCompatibleMediaAdapter.class), (Executor) Runnable::run);

        assertTrue(service.imageProviders().isEmpty());
    }

    @Test
    void executableVoiceModelsExcludeCloneAndRealtimeVariants() {
        AiProvider provider = configuredProvider("{\"media\":{\"voice\":[\"qwen3-tts-vd-demo\",\"qwen3-tts-vc-demo\",\"qwen3-tts-flash-realtime\",\"qwen3-tts-instruct-flash-realtime-2026-01-22\",\"MiniMax/voice-clone\",\"qwen3-tts-flash\"]}}");
        MediaGenerationService service = configuredService(provider, mock(MediaGenerationTaskRepo.class));

        assertEquals(java.util.List.of("qwen3-tts-flash"), service.imageProviders().get(0).get("voiceModels"));
    }

    @Test
    void omittedImageModelSelectsStrongestExecutableProviderModelWithoutCallingRemote() {
        AiProvider provider = configuredProvider("{\"media\":{\"image\":[\"qwen-image-max\",\"qwen-image-2.0-pro\",\"qwen-image-3.0\",\"qwen-image-3.0-pro\"]}}");
        MediaGenerationTaskRepo taskRepo = mock(MediaGenerationTaskRepo.class);
        MediaGenerationService service = configuredService(provider, taskRepo);
        MediaGenerationService.ImageRequest request = new MediaGenerationService.ImageRequest();
        request.setProviderId(provider.getId());
        request.setPrompt("product image");
        request.setConfirm(true);

        MediaGenerationService.Task task = service.image(request);

        assertEquals("qwen-image-3.0-pro", task.getModel());
    }

    @Test
    void requiredModelRejectsFilteredAndUnregisteredVoiceModelsBeforeCallingRemote() {
        AiProvider provider = configuredProvider("{\"media\":{\"voice\":[\"qwen3-tts-vd-demo\",\"qwen3-tts-flash\"]}}");
        MediaGenerationService service = configuredService(provider, mock(MediaGenerationTaskRepo.class));

        for (String model : java.util.List.of("qwen3-tts-vd-demo", "qwen3-tts-instruct-flash-realtime-2026-01-22", "not-configured")) {
            MediaGenerationService.VoiceRequest request = new MediaGenerationService.VoiceRequest();
            request.setProviderId(provider.getId());
            request.setInput("test voice");
            request.setModel(model);
            request.setConfirm(true);
            assertThrows(IllegalArgumentException.class, () -> service.voice(request));
        }
    }

    @Test
    void pollWorkerPersistsStableAdapterErrorCode() throws Exception {
        AiProviderRepo providers = mock(AiProviderRepo.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        MediaGenerationTaskRepo taskRepo = mock(MediaGenerationTaskRepo.class);
        OpenAiCompatibleMediaAdapter adapter = mock(OpenAiCompatibleMediaAdapter.class);
        MediaGenerationTask persisted = new MediaGenerationTask();
        persisted.setTaskKey("video-1");
        when(taskRepo.findByTaskKey("video-1")).thenReturn(Optional.of(persisted));
        when(cipher.decrypt(any())).thenReturn("secret");
        when(adapter.pollVideo(any(), any())).thenThrow(new OpenAiCompatibleMediaAdapter.MediaAdapterException("RATE_LIMITED", "供应商限流"));

        MediaGenerationService service = new MediaGenerationService(
                providers, cipher, mock(MaterialService.class), mock(MediaProviderCatalog.class), new AppProps(), new ObjectMapper(),
                taskRepo, mock(AudioContractService.class), adapter, (Executor) Runnable::run);
        MediaGenerationService.Task task = new MediaGenerationService.Task();
        task.setId("video-1");
        task.setProgress(25);
        AiProvider provider = new AiProvider();
        provider.setBaseUrl("https://api.openai.com");
        provider.setApiKey("encrypted-key");

        Method worker = MediaGenerationService.class.getDeclaredMethod("pollVideoWorker", MediaGenerationService.Task.class, AiProvider.class, String.class, String.class);
        worker.setAccessible(true);
        worker.invoke(service, task, provider, "video-model", "remote-1");

        assertEquals("RATE_LIMITED", persisted.getErrorCode());
        assertEquals("failed_terminal", task.getStatus());
    }

    private AiProvider configuredProvider(String models) {
        AiProvider provider = new AiProvider();
        provider.setId(9L);
        provider.setName("test provider");
        provider.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        provider.setApiKey("encrypted-key");
        provider.setEnabled(true);
        provider.setModels(models);
        return provider;
    }

    private MediaGenerationService configuredService(AiProvider provider, MediaGenerationTaskRepo taskRepo) {
        AiProviderRepo providerRepo = mock(AiProviderRepo.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        when(providerRepo.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(providerRepo.findByEnabledTrueOrderByPriorityAsc()).thenReturn(java.util.List.of(provider));
        when(cipher.decrypt("encrypted-key")).thenReturn("sk-test");
        ObjectMapper mapper = new ObjectMapper();
        MediaProviderCatalog catalog = new MediaProviderCatalog(mapper);
        OpenAiCompatibleMediaAdapter adapter = new OpenAiCompatibleMediaAdapter(mapper, request -> {
            throw new AssertionError("remote media calls are forbidden in this test");
        });
        return new MediaGenerationService(providerRepo, cipher, mock(MaterialService.class), catalog,
                new AppProps(), mapper, taskRepo, mock(AudioContractService.class), adapter, command -> { });
    }
}
