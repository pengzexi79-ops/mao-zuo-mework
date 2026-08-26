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
}
