package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.MaterialFolderRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 质量准入在 MaterialService 注册/重新探测链路上的强制执行测试：
 * 真实 ffmpeg 生成素材 + 真实闸门，库与 AI 用 mock。原文件全程不被删除或改写。
 */
@ExtendWith(MockitoExtension.class)
class QualityGateEnforcementTest {

    @Mock private MaterialStore materialRepo;
    @Mock private MaterialFolderRepo folderRepo;
    @Mock private AiService aiService;

    private void assumeFfmpeg() {
        ProcRunner runner = new ProcRunner();
        AppProps props = new AppProps();
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "ffmpeg unavailable; integration test skipped");
        assumeTrue(runner.available(props.getFfprobe(), "-version"), "ffprobe unavailable; integration test skipped");
    }

    private Path generate(Path work, String name, List<String> args) throws Exception {
        Path out = work.resolve(name);
        List<String> cmd = new java.util.ArrayList<>(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error"));
        cmd.addAll(args);
        cmd.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", out.toString()));
        ProcRunner.Result r = new ProcRunner().run(cmd, 120);
        assertTrue(r.ok(), r.out());
        return out;
    }

    private MaterialService service(Path dataDir, MaterialDiagnosisService diagnosis) {
        AppProps props = new AppProps();
        props.setDataDir(dataDir.toString());
        props.setMaterialsDir(dataDir.resolve("materials").toString());
        props.setCacheDir(dataDir.resolve("cache").toString());
        FfmpegTool ffmpeg = new FfmpegTool(props, new ProcRunner());
        return new MaterialService(materialRepo, folderRepo, ffmpeg, props, aiService, Runnable::run, diagnosis);
    }

    private MaterialDiagnosisService diagnosis(Path dataDir) {
        AppProps props = new AppProps();
        props.setDataDir(dataDir.toString());
        FfmpegTool ffmpeg = new FfmpegTool(props, new ProcRunner());
        return new MaterialDiagnosisService(props, null, null, ffmpeg, new ProcRunner());
    }

    private void stubStore(Material[] savedSlot) {
        // register() 首次查询返回空；素材落库后 findByFilePath/findById 返回同一实例（模拟 DB 行为）。
        lenient().when(materialRepo.findByFilePath(any())).thenAnswer(invocation ->
                savedSlot != null && savedSlot[0] != null ? Optional.of(savedSlot[0]) : Optional.empty());
        lenient().when(materialRepo.save(any(Material.class))).thenAnswer(invocation -> {
            Material material = invocation.getArgument(0);
            if (material.getId() == null) material.setId(1L);
            if (savedSlot != null) savedSlot[0] = material;
            return material;
        });
        lenient().when(materialRepo.findById(any())).thenAnswer(invocation ->
                savedSlot != null && savedSlot[0] != null ? Optional.of(savedSlot[0]) : Optional.empty());
    }

    @Test
    void pureColorVideoRegistrationIsMarkedFailedWithActionableTag() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-enforce-color-");
        Path color = generate(work, "blueclip.mp4", List.of(
                "-f", "lavfi", "-i", "color=c=blue:s=360x640:r=30:d=2", "-an"));
        Material[] saved = new Material[1];
        stubStore(saved);
        MaterialService service = service(work, diagnosis(work));

        Material m = service.register(color.toString(), null, false, Material.Source.local, null);

        assertEquals(Material.Status.failed, m.getStatus(), "pure color video must be marked unavailable");
        assertTrue(m.getTags() != null && m.getTags().contains("低质"), "tags=" + m.getTags());
        assertTrue(m.getTags().contains("纯色"), "actionable reason tag expected, tags=" + m.getTags());
        assertTrue(Files.exists(color), "user original file must never be deleted");
    }

    @Test
    void silenceOnlyAudioRegistrationIsMarkedFailed() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-enforce-audio-");
        Path silence = work.resolve("empty.wav");
        assertTrue(new ProcRunner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-t", "2", silence.toString()), 120).ok());
        Material[] saved = new Material[1];
        stubStore(saved);
        MaterialService service = service(work, diagnosis(work));

        Material m = service.register(silence.toString(), null, false, Material.Source.local, null);

        assertEquals(Material.Status.failed, m.getStatus());
        assertTrue(m.getTags() != null && m.getTags().contains("低质"), "tags=" + m.getTags());
        assertTrue(Files.exists(silence), "user original file must never be deleted");
    }

    @Test
    void dynamicVideoRegistrationStaysReady() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-enforce-dynamic-");
        Path dynamic = generate(work, "realclip.mp4", List.of(
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-t", "2", "-an"));
        Material[] saved = new Material[1];
        stubStore(saved);
        MaterialService service = service(work, diagnosis(work));

        Material m = service.register(dynamic.toString(), null, false, Material.Source.local, null);

        assertEquals(Material.Status.ready, m.getStatus());
        assertTrue(m.getTags() == null || !m.getTags().contains("低质"), "tags=" + m.getTags());
    }

    @Test
    void reprobeSelfHealsAfterContentIsReplaced() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-enforce-heal-");
        Path frame = work.resolve("frame.png");
        assertTrue(new ProcRunner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-frames:v", "1", frame.toString()), 120).ok());
        Path file = generate(work, "fixme.mp4", List.of(
                "-loop", "1", "-i", frame.toString(), "-t", "3", "-an"));
        Material[] saved = new Material[1];
        stubStore(saved);
        MaterialService service = service(work, diagnosis(work));

        Material rejected = service.register(file.toString(), null, false, Material.Source.local, null);
        assertEquals(Material.Status.failed, rejected.getStatus(), "digital still must be rejected initially");

        // 用户替换原文件内容（同一路径），重新探测后应自愈恢复可用。
        assertTrue(new ProcRunner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=30", "-t", "2", "-an",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", file.toString()), 120).ok());
        Material healed = service.reprobe(rejected.getId());

        assertEquals(Material.Status.ready, healed.getStatus(), "reprobe must restore ready after content fixed");
        assertTrue(healed.getTags() == null || !healed.getTags().contains("低质"), "tags=" + healed.getTags());
    }

    @Test
    void imageRegistrationIsNeverFailedByTheGate() throws Exception {
        assumeFfmpeg();
        Path work = Files.createTempDirectory("gate-enforce-image-");
        Path image = work.resolve("product.png");
        assertTrue(new ProcRunner().run(List.of(new AppProps().getFfmpeg(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=360x640:rate=1", "-frames:v", "1", image.toString()), 120).ok());
        Material[] saved = new Material[1];
        stubStore(saved);
        MaterialService service = service(work, diagnosis(work));

        Material m = service.register(image.toString(), null, false, Material.Source.local, null);

        assertEquals(Material.Status.ready, m.getStatus(), "static images must stay usable for manual/product use");
        assertTrue(m.getTags() == null || !m.getTags().contains("低质"), "tags=" + m.getTags());
        assertTrue(Files.exists(image));
    }
}
