package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.external.CrawlerGateway;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.repository.Repositories.AiProviderRepo;
import com.douyin.mixcut.repository.Repositories.PluginRepo;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.repository.Repositories.SkillDefRepo;
import com.douyin.mixcut.repository.Repositories.WorkflowRepo;
import com.douyin.mixcut.security.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BootstrapService 能力中心行为测试：清单驱动的 capabilities 输出契约、
 * 修复安装只允许清单内固定版本（浏览器输入永不为命令行参数）、
 * 便携 Python 与离线 Whisper 模型缓存的环境自检。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BootstrapServiceCapabilitiesTest {

    @TempDir
    Path temp;

    @Mock private WorkflowRepo workflowRepo;
    @Mock private ProjectRepo projectRepo;
    @Mock private SkillDefRepo skillRepo;
    @Mock private SkillEngine skillEngine;
    @Mock private FfmpegTool ffmpeg;
    @Mock private ProcRunner runner;
    @Mock private CrawlerGateway crawler;
    @Mock private JobService jobService;
    @Mock private CrawlJobService crawlJobService;
    @Mock private MaterialService materialService;
    @Mock private AiProviderRepo providerRepo;
    @Mock private PluginRepo pluginRepo;
    @Mock private CredentialCipher credentialCipher;
    @Mock private DataSource dataSource;

    private AppProps props;
    private BootstrapService bootstrap;

    @BeforeEach
    void setUp() {
        props = new AppProps();
        props.setDataDir(temp.resolve("data").toString());
        props.setMaterialsDir(temp.resolve("data/materials").toString());
        props.setOutputDir(temp.resolve("data/output").toString());
        props.setCacheDir(temp.resolve("data/cache").toString());
        props.setLocalPython("python");
        bootstrap = new BootstrapService(props, workflowRepo, projectRepo, skillRepo, skillEngine,
                ffmpeg, runner, crawler, jobService, crawlJobService, materialService,
                providerRepo, pluginRepo, credentialCipher, dataSource, new CredentialRegistry());
        // 默认全部探测失败；具体测试再按命令内容打开对应探测。
        when(runner.run(any(), anyLong())).thenReturn(new ProcRunner.Result(1, "not available"));
        when(ffmpeg.ffmpegAvailable()).thenReturn(false);
        when(ffmpeg.ffprobeAvailable()).thenReturn(false);
        when(crawler.ytdlpAvailable()).thenReturn(false);
        when(crawler.yougetAvailable()).thenReturn(false);
        when(credentialCipher.available()).thenReturn(false);
    }

    private void makeAllProbesReady() {
        when(ffmpeg.ffmpegAvailable()).thenReturn(true);
        when(ffmpeg.ffprobeAvailable()).thenReturn(true);
        when(crawler.ytdlpAvailable()).thenReturn(true);
        when(crawler.yougetAvailable()).thenReturn(true);
        when(runner.available(anyString(), anyString())).thenReturn(true);
        when(runner.available(anyList(), anyString())).thenReturn(true);
        when(runner.run(any(), anyLong())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            String joined = String.join(" ", cmd);
            if (joined.contains("importlib.util.find_spec")) return new ProcRunner.Result(0, "ok");
            if (cmd.contains("--version") || cmd.contains("-version") || cmd.contains("--help")) {
                return new ProcRunner.Result(0, "ok");
            }
            if (cmd.contains("-m") && cmd.contains("pip")) return new ProcRunner.Result(0, "ok");
            return new ProcRunner.Result(1, "not available");
        });
    }

    @Test
    void capabilitiesAreDrivenByManifestWithStableContract() {
        makeAllProbesReady();
        List<Map<String, Object>> caps = bootstrap.capabilities();
        assertEquals(21, caps.size(), "能力中心共 21 项（16 检测 + 5 外部）");
        List<String> keys = caps.stream().map(cap -> String.valueOf(cap.get("key"))).toList();
        assertEquals(List.of("video-render", "database", "video-download", "video-download-2", "asr",
                "asr-local", "ocr", "tts", "chattts", "loudness", "vocals", "matting", "auto-editor",
                "opencv", "magick", "image-gallery", "whisper-model", "nvenc", "pixabay-video",
                "pexels-video", "freesound"), keys, "能力顺序与清单一致");

        Map<String, Object> render = caps.get(0);
        assertEquals("出片与渲染", render.get("group"));
        assertEquals("视频渲染与拼接", render.get("name"));
        assertEquals("FFmpeg", render.get("tool"));
        assertEquals(true, render.get("wired"));
        assertEquals("出片任务、字幕烧录、静音封装、媒体质检", render.get("usedBy"));
        assertEquals("ready", render.get("status"));
        assertEquals(false, render.get("needsNetwork"));
        assertEquals("bundled", render.get("installMode"));
        assertEquals("none", render.get("action"));
        assertEquals("已安装可用", render.get("actionLabel"));
        assertEquals("https://ffmpeg.org/download.html", render.get("officialUrl"));
        assertEquals(true, render.get("installed"));
        assertEquals(true, render.get("runtimeReady"));
        assertEquals(false, render.get("fallback"));
        assertEquals(false, render.get("activationRequired"));
        assertEquals("已安装并接入默认链路，无需操作。", render.get("guide"));

        Map<String, Object> vocals = caps.stream().filter(cap -> "vocals".equals(cap.get("key"))).findFirst().orElseThrow();
        assertEquals("ready", vocals.get("status"), "demucs 探测通过时应为 ready");
        assertEquals(true, vocals.get("needsNetwork"));
        assertEquals("素材库人声/伴奏分离", vocals.get("usedBy"));

        Map<String, Object> external = caps.stream().filter(cap -> "freesound".equals(cap.get("key"))).findFirst().orElseThrow();
        assertEquals("external", external.get("status"));
        assertEquals(true, external.get("needsNetwork"));
        assertEquals("authorization", external.get("installMode"));
        assertEquals("configure", external.get("action"));
        assertEquals("配置 API Key", external.get("actionLabel"));
        assertEquals(false, external.get("configured"));
        assertEquals("完成官方授权后可用于素材检索", external.get("pipelineStages"));
        assertEquals("https://freesound.org/apiv2/apply/", external.get("officialUrl"));

        Map<String, Object> pexels = caps.stream().filter(cap -> "pexels-video".equals(cap.get("key"))).findFirst().orElseThrow();
        assertEquals("external", pexels.get("status"));
        assertEquals("authorization", pexels.get("installMode"));
        assertEquals("https://www.pexels.com/api/", pexels.get("officialUrl"));
        assertTrue(pexels.get("credential") instanceof Map, "Pexels 能力必须声明可配置凭据");
        Map<?, ?> pexelsCredential = (Map<?, ?>) pexels.get("credential");
        assertEquals("pexels", pexelsCredential.get("configId"));
        assertEquals("APP_PEXELS_API_KEY", pexelsCredential.get("variable"));
        assertEquals(true, pexelsCredential.get("restartRequired"));
    }

    @Test
    void missingCapabilitiesMarkOnlyManifestRepairableKeysAsRepairable() {
        List<Map<String, Object>> caps = bootstrap.capabilities();
        Map<String, Object> vocals = caps.stream().filter(cap -> "vocals".equals(cap.get("key"))).findFirst().orElseThrow();
        assertEquals("missing", vocals.get("status"));
        assertEquals("repairable", vocals.get("installMode"));
        assertEquals("install", vocals.get("action"));
        assertEquals("修复安装", vocals.get("actionLabel"));
        assertEquals("该能力应随安装包预置。缺失时可联网修复安装；若失败，请重新运行安装器。", vocals.get("guide"));

        Map<String, Object> gallery = caps.stream().filter(cap -> "image-gallery".equals(cap.get("key"))).findFirst().orElseThrow();
        assertEquals("repairable", gallery.get("installMode"), "image-gallery 是清单允许修复安装的第二项");

        Map<String, Object> matting = caps.stream().filter(cap -> "matting".equals(cap.get("key"))).findFirst().orElseThrow();
        assertEquals("bundled", matting.get("installMode"), "matting 仅随包分发，不可修复安装");
        assertEquals("official", matting.get("action"));
        assertEquals("查看安装说明", matting.get("actionLabel"));
        assertEquals("该能力应随安装包预置。缺失时请重新运行安装器的运行环境检查，不会在页面中伪装为可用。", matting.get("guide"));
    }

    @Test
    void installCapabilityUsesOnlyPinnedSpecFromManifest() {
        makeAllProbesReady();
        Map<String, Object> result = bootstrap.installCapability("vocals");
        assertEquals(true, result.get("ok"));
        assertEquals("Demucs（人声/伴奏分离） 已安装可用。", result.get("message"));

        List<String> pipCmd = capturedPipCommand();
        assertEquals(List.of(props.localPythonPath(), "-m", "pip", "install",
                "--disable-pip-version-check", "--no-input", "demucs==4.1.0"), pipCmd,
                "修复安装命令只允许清单固定版本，不携带其他参数");
    }

    @Test
    void installCapabilityForGalleryUsesItsOwnPinnedSpec() {
        makeAllProbesReady();
        Map<String, Object> result = bootstrap.installCapability("image-gallery");
        assertEquals(true, result.get("ok"));
        assertEquals("gallery-dl（图片/图集抓取） 已安装可用。", result.get("message"));
        assertTrue(capturedPipCommand().contains("gallery-dl==1.32.9"), "gallery-dl 修复目标必须固定版本");
    }

    private List<String> capturedPipCommand() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(runner, org.mockito.Mockito.atLeastOnce()).run(captor.capture(), anyLong());
        return captor.getAllValues().stream()
                .filter(cmd -> cmd.contains("-m") && cmd.contains("pip"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未捕获到 pip install 命令"));
    }

    @Test
    void installCapabilityNeverRunsPipForNonRepairableOrUnknownKeys() {
        Map<String, Object> matting = bootstrap.installCapability("matting");
        assertEquals(false, matting.get("ok"));
        assertEquals(true, matting.get("guide"));
        assertEquals("matting 无法自动安装（需从官方发布页下载便携版）：请打开「环境中心」查看官方链接并按其步骤操作。", matting.get("message"));

        Map<String, Object> asrLocal = bootstrap.installCapability("asr-local");
        assertTrue(String.valueOf(asrLocal.get("message")).startsWith("whisper.cpp 无法自动安装"),
                "asr-local 使用清单 installDisplay 文案");

        Map<String, Object> unknown = bootstrap.installCapability("some-arbitrary-package");
        assertEquals(false, unknown.get("ok"));
        assertTrue(String.valueOf(unknown.get("message")).contains("some-arbitrary-package 无法自动安装"));

        Map<String, Object> chattts = bootstrap.installCapability("chattts");
        assertEquals(false, chattts.get("ok"));
        assertEquals(true, chattts.get("guide"));
        assertTrue(String.valueOf(chattts.get("message")).contains("ChatTTS 需要兼容的 Python"));

        Map<String, Object> blank = bootstrap.installCapability("   ");
        assertEquals(false, blank.get("ok"));
        assertEquals("缺少能力标识", blank.get("message"));

        verify(runner, never()).run(argThat(cmd -> cmd.contains("-m") && cmd.contains("pip")), anyLong());
    }

    @Test
    void installCapabilityReportsFailedInstallWithoutSilentlyMarkingReady() {
        // pip 安装成功但模块探测仍失败：必须报告"未通过"，绝不伪装可用。
        when(runner.run(any(), anyLong())).thenAnswer(invocation -> {
            List<String> cmd = invocation.getArgument(0);
            if (cmd.contains("-m") && cmd.contains("pip")) return new ProcRunner.Result(0, "installed");
            return new ProcRunner.Result(1, "not available");
        });
        Map<String, Object> result = bootstrap.installCapability("vocals");
        assertEquals(false, result.get("ok"));
        assertEquals(true, result.get("guide"));
        assertEquals("Demucs（人声/伴奏分离） 已完成安装，但重新检测未通过。", result.get("message"));
    }

    @Test
    void envExposesPortablePythonAndOfflineWhisperModelChecks() {
        Map<String, Object> env = bootstrap.env();
        assertTrue(env.containsKey("portablePython"), "环境自检必须包含便携 Python 路径");
        assertTrue(env.containsKey("portablePythonReady"), "环境自检必须包含便携 Python 就绪状态");
        String portablePython = String.valueOf(env.get("portablePython"));
        assertEquals(!portablePython.isEmpty(), env.get("portablePythonReady"),
                "portablePythonReady 必须与路径存在性一致");
        assertTrue(env.containsKey("offlineWhisperModels"), "环境自检必须包含离线 Whisper 模型缓存状态");
        Object whisper = env.get("offlineWhisperModels");
        assertTrue(whisper instanceof Map, "offlineWhisperModels 应为 toolState 结构");
        Map<?, ?> state = (Map<?, ?>) whisper;
        assertTrue(state.containsKey("installed") && state.containsKey("integrated") && state.containsKey("status"),
                "offlineWhisperModels 必须包含 installed/integrated/status");
    }

    @Test
    void offlineWhisperCacheReadyPrefersDataCacheThenBundleMarker() throws Exception {
        Path dataDir = temp.resolve("data2");
        String marker = temp.resolve("bundle-refs-main").toString();

        // 1) 数据目录已预置缓存 → ready
        Path seeded = dataDir.resolve("hf-cache").resolve("hub/models--Systran--faster-whisper-small");
        Files.createDirectories(seeded);
        assertTrue(BootstrapService.offlineWhisperCacheReady(dataDir, marker));

        // 2) 数据目录缺失，仅发行包标记文件存在 → ready
        deleteRecursively(seeded);
        Files.writeString(Path.of(marker), "main");
        assertTrue(BootstrapService.offlineWhisperCacheReady(dataDir, marker));

        // 3) 两者都缺失 → missing
        Files.deleteIfExists(Path.of(marker));
        assertFalse(BootstrapService.offlineWhisperCacheReady(dataDir, marker));
        assertFalse(BootstrapService.offlineWhisperCacheReady(dataDir, null));
    }

    @Test
    void envMapKeepsKnownProbeKeysStable() {
        makeAllProbesReady();
        Map<String, Object> env = bootstrap.env(true);
        for (String key : List.of("version", "ffmpeg", "ffprobe", "yt-dlp", "you-get", "localPython",
                "localPythonReady", "fasterWhisper", "rapidOcr", "neuralTts", "chatTts",
                "ffmpegNormalize", "demucs", "galleryDl", "openCv", "autoEditor", "rembg",
                "whisperCpp", "imageMagick", "databaseConnected")) {
            assertTrue(env.containsKey(key), "环境自检必须保留既有探测键：" + key);
        }
        assertEquals("ready", ((Map<?, ?>) env.get("demucs")).get("status"));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignore) {
                }
            });
        }
    }
}
