package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 质量准入闸门的真实媒体测试：全部使用 ffmpeg 现场生成的色卡/动态/静帧/静音/低噪素材，
 * 不依赖数据库（store 传 null，闸门路径不触库）。
 */
class MaterialDiagnosisServiceTest {

    private MaterialDiagnosisService service() {
        return new MaterialDiagnosisService(new AppProps(), null, null,
                new FfmpegTool(new AppProps(), new ProcRunner()), new ProcRunner());
    }

    private ProcRunner runner() {
        return new ProcRunner();
    }

    private void assumeFfmpeg() {
        ProcRunner runner = runner();
        assumeTrue(runner.available(new AppProps().getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        assumeTrue(runner.available(new AppProps().getFfprobe(), "-version"), "ffprobe unavailable; integration test skipped");
    }

    private Path generate(Path work, String name, List<String> args) throws Exception {
        Path out = work.resolve(name);
        List<String> cmd = new java.util.ArrayList<>(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error"));
        cmd.addAll(args);
        cmd.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", out.toString()));
        ProcRunner.Result r = runner().run(cmd, 120);
        assertTrue(r.ok(), r.out());
        return out;
    }

    private Material material(Path file, String name, Material.FileType type) {
        Material m = new Material();
        m.setName(name);
        m.setFilePath(file.toString());
        m.setFileType(type);
        m.setStatus(Material.Status.ready);
        return m;
    }

    @Test
    void rejectsPureColorVideoAsPlaceholder() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-color-");
        Path color = generate(work, "blueclip.mp4", List.of(
                "-f", "lavfi", "-i", "color=c=blue:s=360x640:r=30:d=2", "-an"));

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(color, "blueclip.mp4", Material.FileType.video));
        assertFalse(gate.isAdmitted());
        assertTrue(gate.getReasons().stream().anyMatch(r -> r.contains("纯色")),
                "reasons=" + gate.getReasons());
    }

    @Test
    void rejectsDigitalStillVideoAsImageLike() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-still-");
        Path frame = work.resolve("frame.png");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-frames:v", "1", frame.toString()), 120).ok());
        Path still = generate(work, "stilltext.mp4", List.of(
                "-loop", "1", "-i", frame.toString(), "-t", "3", "-an"));

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(still, "stilltext.mp4", Material.FileType.video));
        assertFalse(gate.isAdmitted());
        assertTrue(gate.getReasons().stream().anyMatch(r -> r.contains("静止")),
                "reasons=" + gate.getReasons());
    }

    @Test
    void admitsDynamicVideo() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-dynamic-");
        Path dynamic = generate(work, "dynamic.mp4", List.of(
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-t", "2", "-an"));

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(dynamic, "dynamic.mp4", Material.FileType.video));
        assertTrue(gate.isAdmitted(), "reasons=" + gate.getReasons());
    }

    @Test
    void halfFrozenVideoWithoutPlaceholderNameIsAdmitted() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-half-");
        Path frame = work.resolve("frame.png");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-frames:v", "1", frame.toString()), 120).ok());
        Path dyn = generate(work, "dyn.mp4", List.of(
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-t", "1.5", "-an"));
        Path still = generate(work, "still.mp4", List.of(
                "-loop", "1", "-i", frame.toString(), "-t", "1.5", "-an"));
        Path half = work.resolve("half.mp4");
        Files.writeString(work.resolve("half-list.txt"),
                "file '" + dyn.toString().replace("\\", "/") + "'\nfile '" + still.toString().replace("\\", "/") + "'\n");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "concat", "-safe", "0", "-i", work.resolve("half-list.txt").toString(),
                "-c:v", "libx264", "-pix_fmt", "yuv420p", half.toString()), 120).ok());

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(half, "half.mp4", Material.FileType.video));
        assertTrue(gate.isAdmitted(), "half-frozen real footage must not be hard-rejected, reasons=" + gate.getReasons());
    }

    @Test
    void halfFrozenVideoWithPlaceholderNameIsRejected() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-half-name-");
        Path frame = work.resolve("frame.png");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-frames:v", "1", frame.toString()), 120).ok());
        Path dyn = generate(work, "dyn.mp4", List.of(
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-t", "1.5", "-an"));
        Path still = generate(work, "still.mp4", List.of(
                "-loop", "1", "-i", frame.toString(), "-t", "1.5", "-an"));
        Path half = work.resolve("demo.mp4");
        Files.writeString(work.resolve("list.txt"),
                "file '" + dyn.toString().replace("\\", "/") + "'\nfile '" + still.toString().replace("\\", "/") + "'\n");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "concat", "-safe", "0", "-i", work.resolve("list.txt").toString(),
                "-c:v", "libx264", "-pix_fmt", "yuv420p", half.toString()), 120).ok());

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(half, "demo.mp4", Material.FileType.video));
        assertFalse(gate.isAdmitted());
        assertTrue(gate.getReasons().stream().anyMatch(r -> r.contains("demo")),
                "reasons=" + gate.getReasons());
    }

    @Test
    void placeholderNameAloneIsOnlyASoftWarning() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-name-only-");
        Path dynamic = generate(work, "demo.mp4", List.of(
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-t", "2", "-an"));

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(dynamic, "demo.mp4", Material.FileType.video));
        assertTrue(gate.isAdmitted(), "dynamic content with demo name must not be hard-rejected");
        assertTrue(gate.getWarnings().stream().anyMatch(w -> w.contains("demo")),
                "warnings=" + gate.getWarnings());
    }

    @Test
    void rejectsSilenceOnlyAudioAsNoiseFloor() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-silence-");
        Path silence = work.resolve("silence.wav");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-t", "2", silence.toString()), 120).ok());

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(silence, "silence.wav", Material.FileType.audio));
        assertFalse(gate.isAdmitted());
        assertTrue(gate.getReasons().stream().anyMatch(r -> r.contains("音量") || r.contains("静音")),
                "reasons=" + gate.getReasons());
    }

    @Test
    void rejectsLowVolumeNoiseLikeAudio() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-lowvol-");
        Path low = work.resolve("noise.wav");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2",
                "-af", "volume=0.001", low.toString()), 120).ok());

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(low, "noise.mp3", Material.FileType.audio));
        assertFalse(gate.isAdmitted());
        assertTrue(gate.getReasons().stream().anyMatch(r -> r.contains("音量")),
                "reasons=" + gate.getReasons());
    }

    @Test
    void admitsToneAudio() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-tone-");
        Path tone = work.resolve("bgm.wav");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=2", tone.toString()), 120).ok());

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(tone, "bgm.mp3", Material.FileType.audio));
        assertTrue(gate.isAdmitted(), "reasons=" + gate.getReasons());
    }

    @Test
    void imageIsNeverRejectedForBeingStatic() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-image-");
        Path image = work.resolve("product.png");
        assertTrue(runner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=1", "-frames:v", "1", image.toString()), 120).ok());

        MaterialDiagnosisService.QualityGateResult gate =
                service().qualityGate(material(image, "product.png", Material.FileType.image));
        assertTrue(gate.isAdmitted(), "static images must be preserved for manual/product use");
        assertTrue(gate.getReasons().isEmpty());
    }
}
