package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.external.ProcessRegistry;
import com.douyin.mixcut.repository.MaterialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AudioEngineServiceTest {
    @TempDir Path temp;
    @Mock private ProcRunner runner;
    @Mock private FfmpegTool ffmpeg;
    @Mock private MaterialStore materialRepo;
    @Mock private MaterialService materialService;
    @Mock private TtsService ttsService;

    @Test
    void separatesExistingMaterialIntoVoiceAndBgmMaterials() throws Exception {
        AppProps props = new AppProps();
        props.setDataDir(temp.resolve("data").toString());
        props.setMaterialsDir(temp.resolve("data/materials").toString());
        props.setCacheDir(temp.resolve("data/cache").toString());
        props.setLocalPython("python");
        Path sourceFile = temp.resolve("source.mp4");
        Files.writeString(sourceFile, "media");
        Material source = new Material();
        source.setId(7L);
        source.setName("source.mp4");
        source.setFileType(Material.FileType.video);
        source.setFilePath(sourceFile.toString());
        when(materialRepo.findById(7L)).thenReturn(Optional.of(source));
        FfmpegTool.MediaInfo info = new FfmpegTool.MediaInfo();
        info.setHasAudio(true);
        info.setAudioDuration(12);
        when(ffmpeg.probe(org.mockito.ArgumentMatchers.eq(sourceFile.toString()), any(ProcessRegistry.CancellationContext.class))).thenReturn(info);
        when(runner.run(any(), anyLong())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("-c") && cmd.contains("import demucs")) return new ProcRunner.Result(0, "ok");
            if (cmd.contains("-vn") && cmd.contains("-ar")) {
                Files.writeString(Path.of(cmd.get(cmd.size() - 1)), "a".repeat(2048));
                return new ProcRunner.Result(0, "ok");
            }
            if (cmd.contains("-m") && cmd.contains("demucs")) {
                Path outRoot = Path.of(cmd.get(cmd.indexOf("-o") + 1));
                Path modelDir = outRoot.resolve("htdemucs").resolve("input");
                Files.createDirectories(modelDir);
                Files.writeString(modelDir.resolve("vocals.wav"), "vocals");
                Files.writeString(modelDir.resolve("no_vocals.wav"), "music");
                return new ProcRunner.Result(0, "ok");
            }
            return new ProcRunner.Result(1, "unexpected");
        });
        when(materialService.register(argThat(path -> path.endsWith(".wav")), any(), any(Boolean.class), any(), any(), any(ProcessRegistry.CancellationContext.class)))
                .thenAnswer(invocation -> {
                    Material material = new Material();
                    material.setFilePath(invocation.getArgument(0));
                    material.setFileType(Material.FileType.audio);
                    return material;
                });
        when(materialRepo.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AudioEngineService service = new AudioEngineService(props, runner, ffmpeg, materialRepo, materialService, ttsService);
        AudioEngineService.SeparationResult result = service.separateMaterial(7L);

        assertNotNull(result.getVocals());
        assertNotNull(result.getInstrumental());
        assertEquals(MaterialRole.voice, result.getVocals().getRole());
        assertEquals(MaterialRole.bgm, result.getInstrumental().getRole());
        assertTrue(Files.exists(Path.of(result.getVocals().getFilePath())));
        assertTrue(Files.exists(Path.of(result.getInstrumental().getFilePath())));
    }

    @Test
    void cancellationBeforeSeparationDoesNotCreateStemFiles() throws Exception {
        AppProps props = new AppProps();
        props.setDataDir(temp.resolve("data").toString());
        props.setMaterialsDir(temp.resolve("data/materials").toString());
        props.setLocalPython("python");
        ProcessRegistry registry = new ProcessRegistry();
        ProcessRegistry.CancellationContext context = registry.create("cancelled-separation");
        registry.cancel(context);
        AudioEngineService service = new AudioEngineService(props, runner, ffmpeg, materialRepo, materialService, ttsService);

        assertThrows(java.util.concurrent.CancellationException.class, () -> service.separateMaterial(8L, context));
        assertTrue(Files.notExists(temp.resolve("data/materials/media-tools/generated-audio")));
    }

    @Test
    void rejectsNonAudioVisualMaterials() throws Exception {
        Path image = temp.resolve("image.png");
        Files.writeString(image, "image");
        Material material = new Material();
        material.setId(9L);
        material.setFileType(Material.FileType.image);
        material.setFilePath(image.toString());
        when(materialRepo.findById(9L)).thenReturn(Optional.of(material));
        AudioEngineService service = new AudioEngineService(new AppProps(), runner, ffmpeg, materialRepo, materialService, ttsService);

        assertThrows(IllegalArgumentException.class, () -> service.separateMaterial(9L));
    }
}
