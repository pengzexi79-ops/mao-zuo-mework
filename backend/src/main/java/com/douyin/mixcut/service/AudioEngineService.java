package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.external.ProcessRegistry;
import com.douyin.mixcut.external.TaskAwareProcRunner;
import com.douyin.mixcut.repository.MaterialStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Unified product-facing audio engine backed by the existing local TTS, Demucs and FFmpeg capabilities. */
@Slf4j
@Service
public class AudioEngineService {
    private final AppProps props;
    private final ProcRunner runner;
    private final FfmpegTool ffmpeg;
    private final MaterialStore materialRepo;
    private final MaterialService materialService;
    private final TtsService ttsService;
    private final TaskAwareProcRunner taskRunner;

    public AudioEngineService(AppProps props, ProcRunner runner, FfmpegTool ffmpeg,
                              MaterialStore materialRepo, MaterialService materialService, TtsService ttsService) {
        this(props, runner, ffmpeg, materialRepo, materialService, ttsService, null);
    }

    @Autowired
    public AudioEngineService(AppProps props, ProcRunner runner, FfmpegTool ffmpeg,
                              MaterialStore materialRepo, MaterialService materialService, TtsService ttsService,
                              TaskAwareProcRunner taskRunner) {
        this.props = props;
        this.runner = runner;
        this.ffmpeg = ffmpeg;
        this.materialRepo = materialRepo;
        this.materialService = materialService;
        this.ttsService = ttsService;
        this.taskRunner = taskRunner;
    }

    @Data
    public static class EngineStatus {
        private boolean ffmpeg;
        private boolean ffprobe;
        private boolean tts;
        private boolean naturalTts;
        private boolean separation;
        private String separationProvider = "Demucs";
        private String ttsProvider = "Edge-TTS";
    }

    @Data
    public static class SeparationResult {
        private Material vocals;
        private Material instrumental;
        private String provider = "Demucs";
        private String sourceName;
        private String message;
    }

    public EngineStatus status() {
        EngineStatus status = new EngineStatus();
        status.setFfmpeg(ffmpeg.ffmpegAvailable());
        status.setFfprobe(ffmpeg.ffprobeAvailable());
        status.setTts(ttsService.available());
        status.setNaturalTts(ttsService.naturalAvailable());
        status.setSeparation(demucsAvailable());
        return status;
    }

    public SeparationResult separateMaterial(Long materialId) {
        return separateMaterial(materialId, ProcessRegistry.CancellationContext.none());
    }

    public SeparationResult separateMaterial(Long materialId, ProcessRegistry.CancellationContext context) {
        context.throwIfCancelled();
        Material source = materialRepo.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在"));
        if (source.getFileType() != Material.FileType.audio && source.getFileType() != Material.FileType.video) {
            throw new IllegalArgumentException("只能对音频或带声音的视频执行人声分离");
        }
        Path sourcePath = Path.of(source.getFilePath() == null ? "" : source.getFilePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourcePath)) {
            throw new IllegalArgumentException("素材文件不可读取或不是普通文件");
        }
        FfmpegTool.MediaInfo info = ffmpeg.probe(sourcePath.toString(), context);
        context.throwIfCancelled();
        if (!info.isHasAudio() || info.getAudioDuration() <= 0) throw new IllegalArgumentException("素材没有可分离的音轨");
        context.throwIfCancelled();
        if (!demucsAvailable()) throw new IllegalStateException("人声分离能力不可用：请在能力中心修复 Demucs");
        context.throwIfCancelled();

        String base = safeBaseName(source.getName() == null ? sourcePath.getFileName().toString() : source.getName());
        long stamp = System.currentTimeMillis();
        Path work = props.cache().resolve("audio-engine").resolve("sep-" + source.getId() + "-" + stamp);
        Path finalDir = props.mediaToolsOutput().resolve("generated-audio");
        Path vocalsFinal = null;
        Path instrumentalFinal = null;
        try {
            Files.createDirectories(work);
            Files.createDirectories(finalDir);
            Path input = work.resolve("input.wav");
            context.throwIfCancelled();
            ProcRunner.Result extract = runTask(List.of(props.getFfmpeg(), "-y", "-i", sourcePath.toString(),
                    "-vn", "-ac", "2", "-ar", "44100", input.toString()), 600, context);
            context.throwIfCancelled();
            if (!extract.ok() || !Files.isRegularFile(input) || Files.size(input) < 1024) {
                throw new IllegalStateException("音频提取失败：" + concise(extract.out()));
            }
            Path demucsOut = work.resolve("demucs");
            context.throwIfCancelled();
            ProcRunner.Result separated = runTask(List.of(props.localPythonPath(), "-m", "demucs",
                    "--two-stems", "vocals", "-o", demucsOut.toString(), input.toString()), 3600, context);
            context.throwIfCancelled();
            if (!separated.ok()) throw new IllegalStateException("Demucs 分离失败：" + concise(separated.out()));

            Path vocals = findStem(demucsOut, "vocals.wav");
            Path instrumental = findInstrumentalStem(demucsOut);
            if (vocals == null || instrumental == null) {
                throw new IllegalStateException("Demucs 未生成标准 vocals/no_vocals 输出");
            }
            vocalsFinal = finalDir.resolve(base + "_vocals_" + stamp + ".wav");
            instrumentalFinal = finalDir.resolve(base + "_instrumental_" + stamp + ".wav");
            context.throwIfCancelled();
            Files.copy(vocals, vocalsFinal, StandardCopyOption.REPLACE_EXISTING);
            context.throwIfCancelled();
            Files.copy(instrumental, instrumentalFinal, StandardCopyOption.REPLACE_EXISTING);
            context.throwIfCancelled();

            Material vocalsMaterial = materialService.register(vocalsFinal.toString(), source.getFolderId(), false, Material.Source.generated, null, context);
            vocalsMaterial.setName(base + " 人声分离");
            vocalsMaterial.setRole(MaterialRole.voice);
            vocalsMaterial.setTags(appendTag(source.getTags(), "人声分离,Demucs,vocals,source#" + source.getId()));
            context.throwIfCancelled();
            vocalsMaterial = materialRepo.save(vocalsMaterial);
            context.throwIfCancelled();

            context.throwIfCancelled();
            Material instrumentalMaterial = materialService.register(instrumentalFinal.toString(), source.getFolderId(), false, Material.Source.generated, null, context);
            context.throwIfCancelled();
            instrumentalMaterial.setName(base + " 伴奏分离");
            instrumentalMaterial.setRole(MaterialRole.bgm);
            instrumentalMaterial.setTags(appendTag(source.getTags(), "人声分离,Demucs,instrumental,source#" + source.getId()));
            context.throwIfCancelled();
            instrumentalMaterial = materialRepo.save(instrumentalMaterial);
            context.throwIfCancelled();

            SeparationResult result = new SeparationResult();
            result.setSourceName(source.getName());
            result.setVocals(vocalsMaterial);
            result.setInstrumental(instrumentalMaterial);
            result.setMessage("已分离人声和伴奏，并作为素材入库");
            return result;
        } catch (RuntimeException | Error e) {
            deleteIfExists(vocalsFinal);
            deleteIfExists(instrumentalFinal);
            throw e;
        } catch (Exception e) {
            deleteIfExists(vocalsFinal);
            deleteIfExists(instrumentalFinal);
            throw new IllegalStateException("音频分离处理失败：" + e.getMessage(), e);
        } finally {
            deleteTree(work);
        }
    }

    private void deleteIfExists(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (Exception cleanup) { log.debug("audio output cleanup failed: {}", cleanup.toString()); }
    }

    private ProcRunner.Result runTask(List<String> command, long timeoutSec,
                                      ProcessRegistry.CancellationContext context) {
        return taskRunner == null ? runner.run(command, timeoutSec) : taskRunner.run(command, timeoutSec, context);
    }

    private boolean demucsAvailable() {
        return runner.run(List.of(props.localPythonPath(), "-c", "import demucs"), 12).ok();
    }

    private Path findStem(Path root, String filename) throws java.io.IOException {
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> paths = Files.walk(root, 6)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> filename.equalsIgnoreCase(path.getFileName().toString()))
                    .findFirst().orElse(null);
        }
    }

    private Path findInstrumentalStem(Path root) throws java.io.IOException {
        Path noVocals = findStem(root, "no_vocals.wav");
        return noVocals != null ? noVocals : findStem(root, "instrumental.wav");
    }

    private String safeBaseName(String value) {
        String raw = value == null ? "audio" : value.replaceFirst("\\.[^.]+$", "");
        String safe = raw.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]+", "_").replaceAll("[. ]+$", "").trim();
        if (safe.isBlank()) safe = "audio";
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private String appendTag(String existing, String added) {
        String prefix = existing == null || existing.isBlank() ? "" : existing.trim() + ",";
        return prefix + added;
    }

    private String concise(String value) {
        if (value == null || value.isBlank()) return "无可读错误输出";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(normalized.length() - 500);
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception e) {
            log.debug("audio engine cleanup failed: {}", e.toString());
        }
    }
}
